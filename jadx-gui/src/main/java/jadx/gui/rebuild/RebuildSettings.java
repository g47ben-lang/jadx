package jadx.gui.rebuild;

/**
 * Persisted settings for the APK decode/rebuild/sign pipeline (stored inside jadx-gui settings
 * JSON). Apktool itself is not bundled with jadx - it's Apache-2.0 licensed and distributed as a
 * standalone jar, so the user points jadx at a local copy the same way apk-editor-studio does.
 */
public class RebuildSettings {
	private String apktoolJarPath = "";

	public String getApktoolJarPath() {
		return apktoolJarPath;
	}

	public void setApktoolJarPath(String apktoolJarPath) {
		this.apktoolJarPath = apktoolJarPath;
	}
}
