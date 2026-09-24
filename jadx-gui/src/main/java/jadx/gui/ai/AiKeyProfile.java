package jadx.gui.ai;

/**
 * One "account": a provider + model + API key. {@link AiSettings} holds a list of these so the
 * app can automatically fail over to the next one when a key runs out of quota or is rejected.
 */
public class AiKeyProfile {
	private String name = "";
	private AiProvider provider = AiProvider.GEMINI;
	private String baseUrl = AiProvider.GEMINI.getDefaultBaseUrl();
	private String model = AiProvider.GEMINI.getDefaultModel();
	private String apiKey = "";

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public AiProvider getProvider() {
		return provider;
	}

	public void setProvider(AiProvider provider) {
		this.provider = provider;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	/**
	 * Label shown in the profile list: the user-given name, or a fallback built from the provider/model.
	 */
	public String getDisplayLabel() {
		if (name != null && !name.isBlank()) {
			return name;
		}
		String modelPart = model != null && !model.isBlank() ? " (" + model + ")" : "";
		return provider + modelPart;
	}

	@Override
	public String toString() {
		return getDisplayLabel();
	}
}
