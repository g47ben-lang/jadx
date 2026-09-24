package jadx.gui.ai;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * Persisted AI Assistant configuration (stored inside jadx-gui settings JSON).
 * Network access (proxy, custom trusted CA certificate) must be configured manually,
 * jadx does not attempt to auto-detect a system proxy or filtering software.
 */
public class AiSettings {
	private boolean enabled = false;

	private List<AiKeyProfile> profiles = new ArrayList<>();
	private int activeProfileIndex = 0;

	// legacy single-profile fields, kept only so old settings.json files can still be read;
	// migrated into 'profiles' on load by migrateLegacyIfNeeded(), then left untouched
	private AiProvider provider = AiProvider.GEMINI;
	private String baseUrl = AiProvider.GEMINI.getDefaultBaseUrl();
	private String model = AiProvider.GEMINI.getDefaultModel();
	private String apiKey = "";

	private String proxyHost = "";
	private String proxyPort = "";
	private String proxyUsername = "";
	private String proxyPassword = "";
	private String customCaCertPath = "";
	private boolean trustSystemCertStore = false;

	/**
	 * Moves a pre-multi-profile legacy config (single provider/baseUrl/model/apiKey fields)
	 * into the new profiles list. Safe to call every time settings are loaded: a no-op once
	 * 'profiles' is non-empty.
	 */
	public void migrateLegacyIfNeeded() {
		if (!profiles.isEmpty()) {
			return;
		}
		if (apiKey != null && !apiKey.isBlank()) {
			AiKeyProfile legacy = new AiKeyProfile();
			legacy.setProvider(provider);
			legacy.setBaseUrl(baseUrl);
			legacy.setModel(model);
			legacy.setApiKey(apiKey);
			profiles.add(legacy);
			activeProfileIndex = 0;
		}
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public List<AiKeyProfile> getProfiles() {
		return profiles;
	}

	public void setProfiles(List<AiKeyProfile> profiles) {
		this.profiles = profiles;
	}

	public int getActiveProfileIndex() {
		return activeProfileIndex;
	}

	public void setActiveProfileIndex(int activeProfileIndex) {
		this.activeProfileIndex = activeProfileIndex;
	}

	public @Nullable AiKeyProfile getActiveProfile() {
		if (profiles.isEmpty()) {
			return null;
		}
		int idx = Math.max(0, Math.min(activeProfileIndex, profiles.size() - 1));
		return profiles.get(idx);
	}

	public String getProxyHost() {
		return proxyHost;
	}

	public void setProxyHost(String proxyHost) {
		this.proxyHost = proxyHost;
	}

	public String getProxyPort() {
		return proxyPort;
	}

	public void setProxyPort(String proxyPort) {
		this.proxyPort = proxyPort;
	}

	public String getProxyUsername() {
		return proxyUsername;
	}

	public void setProxyUsername(String proxyUsername) {
		this.proxyUsername = proxyUsername;
	}

	public String getProxyPassword() {
		return proxyPassword;
	}

	public void setProxyPassword(String proxyPassword) {
		this.proxyPassword = proxyPassword;
	}

	public String getCustomCaCertPath() {
		return customCaCertPath;
	}

	public void setCustomCaCertPath(String customCaCertPath) {
		this.customCaCertPath = customCaCertPath;
	}

	public boolean isTrustSystemCertStore() {
		return trustSystemCertStore;
	}

	public void setTrustSystemCertStore(boolean trustSystemCertStore) {
		this.trustSystemCertStore = trustSystemCertStore;
	}
}
