package org.mably.jenkins.plugins.kanboard;

import static hudson.Util.fixNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.thetransactioncompany.jsonrpc2.client.ConnectionConfigurator;
import com.thetransactioncompany.jsonrpc2.client.JSONRPC2Session;

import hudson.EnvVars;
import hudson.ProxyConfiguration;
import hudson.model.AbstractBuild;
import hudson.model.EnvironmentContributingAction;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;

public class Utils {

	private static final String COMMA = ",";

	private static final Logger logger = Logger.getLogger(Utils.class.getName());

	static final String LOG_SEPARATOR = "----------";

	static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\w+");

	public static final class ApiAuthenticator implements ConnectionConfigurator {

		private String xApiAuthToken;

		public ApiAuthenticator(String apiToken) {
			try {
				byte[] xApiAuthTokenBytes = ("jsonrpc:" + apiToken).getBytes("utf-8");
				this.xApiAuthToken = Base64.encodeBase64String(xApiAuthTokenBytes);
			} catch (UnsupportedEncodingException e) {
				this.xApiAuthToken = "";
				System.out.println(e.getMessage());
			}
		}

		@Override
		public void configure(HttpURLConnection connection) {
			connection.addRequestProperty("X-API-Auth", this.xApiAuthToken);
		}
	}

	public static boolean isInteger(String value) {
		boolean isInteger;
		try {
			Integer.parseInt(value);
			isInteger = true;
		} catch (NumberFormatException e) {
			isInteger = false;
		}
		return isInteger;
	}

	public static Proxy getJenkinsProxy(URL url) {
		Proxy proxy = null;
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins != null) {
			ProxyConfiguration proxyConfig = jenkins.proxy;
			if ((proxyConfig != null) && StringUtils.isNotBlank(proxyConfig.name)
					&& (StringUtils.isBlank(proxyConfig.noProxyHost)
							|| !proxyConfig.noProxyHost.contains(url.getHost()))) {
				proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyConfig.name, proxyConfig.port));
			}
		}
		return proxy;
	}

	public static JSONRPC2Session initJSONRPCSession(String endpoint, String apiToken, String apiTokenCredentialId)
			throws MalformedURLException {

		// The JSON-RPC 2.0 server URL
		URL serverURL = new URL(fixNull(endpoint));

		// Create new JSON-RPC 2.0 client session
		JSONRPC2Session session = new JSONRPC2Session(serverURL);
		String token = getTokenToUse(apiTokenCredentialId, apiToken);
		session.setConnectionConfigurator(new Utils.ApiAuthenticator(token));

		Proxy proxy = getJenkinsProxy(serverURL);
		if (proxy != null) {
			session.getOptions().setProxy(proxy);
		}

		return session;
	}

	public static boolean checkJSONRPCEndpoint(String endpoint) {
		boolean valid = false;
		try {
			URL endpointURL = new URL(fixNull(endpoint));
			Proxy proxy = getJenkinsProxy(endpointURL);
			URLConnection conn;
			if (proxy == null) {
				conn = endpointURL.openConnection();
			} else {
				conn = endpointURL.openConnection(proxy);
			}
			conn.connect();
			BufferedReader bufferedReader = new BufferedReader(
					new InputStreamReader(conn.getInputStream(), Charset.defaultCharset()));
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				valid = line.contains("jsonrpc");
				if (valid) {
					break;
				}
			}
			bufferedReader.close();
		} catch (MalformedURLException e) {
			// the URL is not in a valid form
		} catch (IOException e) {
			// the connection couldn't be established
		}
		return valid;
	}

	public static String encodeFileToBase64Binary(File file) throws IOException {
		byte[] bytes = FileUtils.readFileToByteArray(file);
		byte[] encoded = Base64.encodeBase64(bytes);
		return new String(encoded, Charset.defaultCharset());
	}

	public static File decodeBase64ToBinaryFile(String path, String base64String) throws IOException {
		File file = new File(path);
		byte[] data = Base64.decodeBase64(base64String);
		FileUtils.writeByteArrayToFile(file, data);
		return file;
	}

	public static void exportEnvironmentVariable(AbstractBuild<?, ?> build, final String name, final String value) {

		build.addAction(new EnvironmentContributingAction() {

			public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars envVars) {
				if (envVars != null) {
					envVars.put(name, value);
				}
			}

			public String getUrlName() {
				return null;
			}

			public String getIconFileName() {
				return null;
			}

			public String getDisplayName() {
				return null;
			}
		});
	}

	public static String getImplementationVersion() {
		return Utils.class.getPackage().getImplementationVersion();
	}

	public static String[] getCSVStringValue(AbstractBuild<?, ?> build, TaskListener listener, String line)
			throws MacroEvaluationException, IOException, InterruptedException {
		String[] itemValues;
		if (StringUtils.isNotBlank(line)) {
			String[] items = line.split(COMMA);
			itemValues = new String[items.length];
			for (int i = 0; i < items.length; i++) {
				itemValues[i] = TokenMacro.expandAll(build, listener, items[i]);
			}
		} else {
			itemValues = null;
		}
		return itemValues;
	}

	private static String getTokenToUse(String apiTokenCredentialId, String apiToken) {
		String token = apiToken;
		if (apiTokenCredentialId != null && !apiTokenCredentialId.isEmpty()) {
			StringCredentials credentials = lookupCredentials(apiTokenCredentialId);
			if (credentials != null) {
				logger.fine("Using Integration Token Credential ID.");
				token = credentials.getSecret().getPlainText();
			}
		}
		return token;
	}

	private static StringCredentials lookupCredentials(String credentialId) {
		List<StringCredentials> credentials = CredentialsProvider.lookupCredentials(StringCredentials.class,
				Jenkins.getInstance(), ACL.SYSTEM, Collections.<DomainRequirement> emptyList());
		CredentialsMatcher matcher = CredentialsMatchers.withId(credentialId);
		return CredentialsMatchers.firstOrNull(credentials, matcher);
	}

	public static byte[] fetchURL(URL url) {

		byte[] data = null;

		HttpURLConnection conn = null;
		try {

			Proxy proxy = getJenkinsProxy(url);
			if (proxy == null) {
				conn = (HttpURLConnection) url.openConnection();
			} else {
				conn = (HttpURLConnection) url.openConnection(proxy);
			}

			conn.setRequestMethod("GET");

			data = IOUtils.toByteArray(conn.getInputStream());

		} catch (IOException e) {

		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}

		return data;
	}

	public static EnvVars getGlobalEnvVars() {

		EnvVars envVars;

		Jenkins jenkins = Jenkins.getInstance();

		if (jenkins == null) {

			envVars = new EnvVars();

		} else {

			DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProps = jenkins.getGlobalNodeProperties();

			List<EnvironmentVariablesNodeProperty> envVarNodeProps = nodeProps
					.getAll(EnvironmentVariablesNodeProperty.class);

			if (envVarNodeProps.isEmpty()) {
				envVars = new EnvVars();
			} else {
				envVars = ((EnvironmentVariablesNodeProperty) envVarNodeProps.get(0)).getEnvVars();
			}
		}

		return envVars;
	}

	public static String expandFromGlobalEnvVars(String s) {
		EnvVars envVars = Utils.getGlobalEnvVars();
		return envVars.expand(s);
	}

}
