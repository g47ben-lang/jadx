package jadx.gui.ai;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.core.utils.exceptions.JadxRuntimeException;

/**
 * Minimal client for an OpenAI-compatible "chat/completions" HTTP API, with optional
 * "search_code" tool-calling support so the model can look at the actual decompiled project
 * instead of only answering from general knowledge.
 * Network access (proxy, extra trusted CA certificate) is configured manually
 * from {@link AiSettings}, jadx does not auto-detect system proxies or filtering software.
 */
public class AiClient {
	private static final Logger LOG = LoggerFactory.getLogger(AiClient.class);
	private static final Gson GSON = new Gson();
	private static final Duration TIMEOUT = Duration.ofSeconds(60);
	private static final int MAX_TOOL_ROUNDS = 8;
	private static final int MAX_SEARCH_MATCHES = 10;

	private final AiSettings settings;
	private final AiKeyProfile profile;

	public AiClient(AiSettings settings, AiKeyProfile profile) {
		this.settings = settings;
		this.profile = profile;
	}

	/**
	 * Runs {@link #askWithTools} against the settings' active profile, automatically retrying with
	 * the next profile (wrapping around) when a profile is rejected for being out of quota or
	 * invalid (HTTP 401/403/429). Any other failure (network, bad model name, ...) is not retried,
	 * since switching keys wouldn't fix it.
	 * Blocking call, must be executed on a background thread.
	 */
	public static String askWithToolsAndFailover(AiSettings settings, List<AiChatMessage> messages,
			@Nullable ProjectCodeSearch codeSearch, @Nullable ProjectFileOpener fileOpener)
			throws IOException, InterruptedException {
		List<AiKeyProfile> profiles = settings.getProfiles();
		if (profiles.isEmpty()) {
			throw new JadxRuntimeException("AI Assistant: no API key configured");
		}
		int start = Math.max(0, Math.min(settings.getActiveProfileIndex(), profiles.size() - 1));
		IOException lastError = null;
		for (int i = 0; i < profiles.size(); i++) {
			AiKeyProfile candidate = profiles.get((start + i) % profiles.size());
			try {
				return new AiClient(settings, candidate).askWithTools(messages, codeSearch, fileOpener);
			} catch (AiHttpException e) {
				lastError = e;
				if (e.status == 401 || e.status == 403 || e.status == 429) {
					LOG.warn("AI profile '{}' failed with HTTP {}, trying next profile", candidate.getDisplayLabel(), e.status);
					continue;
				}
				throw e;
			}
		}
		throw lastError;
	}

	/**
	 * Simple one-shot request with no tool use, against this client's specific profile (no failover
	 * - used for the "test connection" button, which is testing one profile on purpose).
	 * Blocking call, must be executed on a background thread.
	 */
	public String sendMessage(List<AiChatMessage> messages) throws IOException, InterruptedException {
		JsonArray requestMessages = new JsonArray();
		for (AiChatMessage m : messages) {
			requestMessages.add(toMessageJson(m));
		}
		return textOf(sendRaw(requestMessages, null));
	}

	/**
	 * Runs a conversation that may involve the AI calling the "search_code" tool to look at the
	 * actual decompiled project before answering.
	 * Blocking call, must be executed on a background thread.
	 */
	public String askWithTools(List<AiChatMessage> messages, @Nullable ProjectCodeSearch codeSearch,
			@Nullable ProjectFileOpener fileOpener) throws IOException, InterruptedException {
		JsonArray requestMessages = new JsonArray();
		for (AiChatMessage m : messages) {
			requestMessages.add(toMessageJson(m));
		}
		JsonArray tools = buildToolsDefinition(codeSearch != null, fileOpener != null);
		for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
			JsonObject responseMessage = sendRaw(requestMessages, tools);
			JsonArray toolCalls = responseMessage.has("tool_calls") && responseMessage.get("tool_calls").isJsonArray()
					? responseMessage.getAsJsonArray("tool_calls")
					: null;
			if (toolCalls == null || toolCalls.isEmpty()) {
				return textOf(responseMessage);
			}
			requestMessages.add(responseMessage);
			for (JsonElement tcEl : toolCalls) {
				JsonObject toolCall = tcEl.getAsJsonObject();
				String id = toolCall.get("id").getAsString();
				JsonObject fn = toolCall.getAsJsonObject("function");
				String name = fn.get("name").getAsString();
				String argumentsJson = fn.has("arguments") ? fn.get("arguments").getAsString() : "{}";
				String toolResult = executeTool(name, argumentsJson, codeSearch, fileOpener);

				JsonObject toolMsg = new JsonObject();
				toolMsg.addProperty("role", "tool");
				toolMsg.addProperty("tool_call_id", id);
				toolMsg.addProperty("content", toolResult);
				requestMessages.add(toolMsg);
			}
		}
		// out of rounds: force a plain text answer from whatever was found so far,
		// instead of failing outright
		requestMessages.add(toMessageJson(new AiChatMessage(AiChatMessage.ROLE_USER,
				"Please give your best answer now based on what you've found so far, without calling any more tools.")));
		return textOf(sendRaw(requestMessages, null));
	}

	private static String executeTool(String name, String argumentsJson, @Nullable ProjectCodeSearch codeSearch,
			@Nullable ProjectFileOpener fileOpener) {
		try {
			JsonObject args = GSON.fromJson(argumentsJson, JsonObject.class);
			if ("search_code".equals(name) && codeSearch != null) {
				String query = args != null && args.has("query") ? args.get("query").getAsString() : "";
				return codeSearch.search(query, MAX_SEARCH_MATCHES);
			}
			if ("open_file".equals(name) && fileOpener != null) {
				String fileName = args != null && args.has("name") ? args.get("name").getAsString() : "";
				return fileOpener.open(fileName);
			}
			return "Tool not available: " + name;
		} catch (Exception e) {
			LOG.warn("{} tool call failed", name, e);
			return name + " failed: " + e.getMessage();
		}
	}

	private static JsonArray buildToolsDefinition(boolean includeCodeSearch, boolean includeFileOpener) {
		JsonArray tools = new JsonArray();
		if (includeCodeSearch) {
			JsonObject queryProp = new JsonObject();
			queryProp.addProperty("type", "string");
			queryProp.addProperty("description", "Text to search for (case-insensitive) in the decompiled Java source code");

			JsonObject props = new JsonObject();
			props.add("query", queryProp);

			JsonArray required = new JsonArray();
			required.add("query");

			JsonObject params = new JsonObject();
			params.addProperty("type", "object");
			params.add("properties", props);
			params.add("required", required);

			JsonObject function = new JsonObject();
			function.addProperty("name", "search_code");
			function.addProperty("description",
					"Search the decompiled Android app's source code for a text string. Use this whenever the user "
							+ "asks about specific behavior, classes, permissions or APIs used in the app they are "
							+ "reverse-engineering, instead of guessing from general knowledge. Returns matching class "
							+ "names with a short code snippet around each match. Call it again with a different query "
							+ "if the first search doesn't find what you need.");
			function.add("parameters", params);

			JsonObject tool = new JsonObject();
			tool.addProperty("type", "function");
			tool.add("function", function);
			tools.add(tool);
		}
		if (includeFileOpener) {
			JsonObject nameProp = new JsonObject();
			nameProp.addProperty("type", "string");
			nameProp.addProperty("description", "Class name (full or simple) or resource file name to open");

			JsonObject props = new JsonObject();
			props.add("name", nameProp);

			JsonArray required = new JsonArray();
			required.add("name");

			JsonObject params = new JsonObject();
			params.addProperty("type", "object");
			params.add("properties", props);
			params.add("required", required);

			JsonObject function = new JsonObject();
			function.addProperty("name", "open_file");
			function.addProperty("description",
					"Open a class or resource file (e.g. AndroidManifest.xml, a specific class you found) directly "
							+ "in the jadx GUI so the user can look at it themselves. Use this after finding something "
							+ "relevant with search_code that the user would want to see in full.");
			function.add("parameters", params);

			JsonObject tool = new JsonObject();
			tool.addProperty("type", "function");
			tool.add("function", function);
			tools.add(tool);
		}
		return tools.isEmpty() ? null : tools;
	}

	private static JsonObject toMessageJson(AiChatMessage m) {
		JsonObject obj = new JsonObject();
		obj.addProperty("role", m.getRole());
		obj.addProperty("content", m.getContent());
		return obj;
	}

	private static String textOf(JsonObject message) {
		JsonElement content = message.get("content");
		return content != null && !content.isJsonNull() ? content.getAsString() : "";
	}

	private JsonObject sendRaw(JsonArray messages, @Nullable JsonArray tools) throws IOException, InterruptedException {
		String baseUrl = trimTrailingSlash(profile.getBaseUrl());
		if (baseUrl.isEmpty()) {
			throw new JadxRuntimeException("AI Assistant: base URL is not set");
		}
		if (profile.getApiKey().isEmpty()) {
			throw new JadxRuntimeException("AI Assistant: API key is not set");
		}
		String url = baseUrl + "/chat/completions";

		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("model", profile.getModel());
		requestBody.add("messages", messages);
		requestBody.addProperty("stream", false);
		if (tools != null && !tools.isEmpty()) {
			requestBody.add("tools", tools);
		}

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + profile.getApiKey())
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody), StandardCharsets.UTF_8))
				.build();

		HttpClient client = buildHttpClient();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		int status = response.statusCode();
		String body = response.body();
		if (status < 200 || status >= 300) {
			throw new AiHttpException(status, "AI request failed with HTTP " + status + ": " + shorten(body));
		}
		return extractMessage(body);
	}

	/** Carries the HTTP status code so failover can tell an auth/quota failure from any other error. */
	public static final class AiHttpException extends IOException {
		public final int status;

		public AiHttpException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private static String shorten(@Nullable String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 500 ? s.substring(0, 500) + "..." : s;
	}

	private static JsonObject extractMessage(String body) throws IOException {
		try {
			JsonObject root = GSON.fromJson(body, JsonObject.class);
			if (root.has("error")) {
				throw new IOException("AI API error: " + root.get("error").toString());
			}
			JsonArray choices = root.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				throw new IOException("AI response has no choices: " + shorten(body));
			}
			JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
			if (message == null) {
				throw new IOException("AI response has no message: " + shorten(body));
			}
			return message;
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to parse AI response: " + shorten(body), e);
		}
	}

	private HttpClient buildHttpClient() {
		HttpClient.Builder builder = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL);

		String proxyHost = settings.getProxyHost();
		if (proxyHost != null && !proxyHost.isBlank()) {
			int proxyPort = parsePort(settings.getProxyPort());
			builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost.trim(), proxyPort)));
			String proxyUser = settings.getProxyUsername();
			if (proxyUser != null && !proxyUser.isBlank()) {
				String proxyPass = settings.getProxyPassword();
				builder.authenticator(new Authenticator() {
					@Override
					protected PasswordAuthentication getPasswordAuthentication() {
						if (getRequestorType() == RequestorType.PROXY) {
							return new PasswordAuthentication(proxyUser, proxyPass == null ? new char[0] : proxyPass.toCharArray());
						}
						return null;
					}
				});
			}
		}

		String caCertPath = settings.getCustomCaCertPath();
		boolean useCustomCa = caCertPath != null && !caCertPath.isBlank();
		if (useCustomCa || settings.isTrustSystemCertStore()) {
			builder.sslContext(buildSslContext(useCustomCa ? caCertPath.trim() : null, settings.isTrustSystemCertStore()));
		}
		return builder.build();
	}

	private static int parsePort(@Nullable String portStr) {
		if (portStr == null || portStr.isBlank()) {
			throw new JadxRuntimeException("AI Assistant: proxy port is not set");
		}
		try {
			return Integer.parseInt(portStr.trim());
		} catch (NumberFormatException e) {
			throw new JadxRuntimeException("AI Assistant: invalid proxy port: " + portStr);
		}
	}

	/**
	 * Builds an SSLContext that trusts the JVM default CA set plus, optionally, a user-provided
	 * CA certificate file and/or the OS certificate store (Windows-ROOT). Needed for network
	 * filters/proxies that perform TLS interception with their own root certificate
	 * (e.g. NetFree and similar parental-control/content filters).
	 */
	private static SSLContext buildSslContext(@Nullable String caCertPath, boolean trustSystemCertStore) {
		try {
			List<X509TrustManager> trustManagers = new ArrayList<>();
			trustManagers.add(loadDefaultTrustManager());
			if (caCertPath != null) {
				trustManagers.add(loadCustomCaTrustManager(caCertPath));
			}
			if (trustSystemCertStore) {
				X509TrustManager systemTm = tryLoadSystemCertStoreTrustManager();
				if (systemTm == null) {
					throw new JadxRuntimeException(
							"AI Assistant: system certificate store trust is only supported on Windows");
				}
				trustManagers.add(systemTm);
			}
			X509TrustManager combined = new CompositeTrustManager(trustManagers);
			SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, new TrustManager[] { combined }, new SecureRandom());
			return sslContext;
		} catch (JadxRuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new JadxRuntimeException("AI Assistant: failed to set up TLS trust", e);
		}
	}

	private static X509TrustManager loadDefaultTrustManager() throws Exception {
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init((KeyStore) null);
		return findX509TrustManager(tmf);
	}

	private static X509TrustManager loadCustomCaTrustManager(String caCertPath) throws Exception {
		KeyStore extraKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
		extraKeyStore.load(null, null);
		try (InputStream in = new FileInputStream(caCertPath)) {
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			int i = 0;
			for (Certificate cert : cf.generateCertificates(in)) {
				extraKeyStore.setCertificateEntry("ai-custom-ca-" + (i++), cert);
			}
		} catch (IOException e) {
			throw new JadxRuntimeException("AI Assistant: failed to load custom CA certificate from: " + caCertPath, e);
		}
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(extraKeyStore);
		return findX509TrustManager(tmf);
	}

	/**
	 * Uses the JDK's built-in SunMSCAPI provider to read the Windows "Trusted Root Certification
	 * Authorities" store, where filters like NetFree install their interception root certificate.
	 * Returns null on non-Windows platforms or if the provider isn't available.
	 */
	private static @Nullable X509TrustManager tryLoadSystemCertStoreTrustManager() {
		try {
			KeyStore systemStore = KeyStore.getInstance("Windows-ROOT");
			systemStore.load(null, null);
			TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			tmf.init(systemStore);
			return findX509TrustManager(tmf);
		} catch (Throwable e) {
			// e.g. NoSuchAlgorithmException, or a LinkageError if the jdk.crypto.mscapi module isn't bundled
			LOG.warn("Windows system certificate store is not available", e);
			return null;
		}
	}

	private static X509TrustManager findX509TrustManager(TrustManagerFactory tmf) {
		for (TrustManager tm : tmf.getTrustManagers()) {
			if (tm instanceof X509TrustManager) {
				return (X509TrustManager) tm;
			}
		}
		throw new JadxRuntimeException("No X509TrustManager found");
	}

	private static String trimTrailingSlash(@Nullable String url) {
		if (url == null) {
			return "";
		}
		String trimmed = url.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}

	private static final class CompositeTrustManager implements X509TrustManager {
		private final List<X509TrustManager> delegates;

		private CompositeTrustManager(List<X509TrustManager> delegates) {
			this.delegates = delegates;
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			delegates.get(0).checkClientTrusted(chain, authType);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			CertificateException lastError = null;
			for (X509TrustManager delegate : delegates) {
				try {
					delegate.checkServerTrusted(chain, authType);
					return;
				} catch (CertificateException e) {
					lastError = e;
				}
			}
			throw lastError != null ? lastError : new CertificateException("No trust managers configured");
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			List<X509Certificate> result = new ArrayList<>();
			for (X509TrustManager delegate : delegates) {
				result.addAll(List.of(delegate.getAcceptedIssuers()));
			}
			return result.toArray(new X509Certificate[0]);
		}
	}
}
