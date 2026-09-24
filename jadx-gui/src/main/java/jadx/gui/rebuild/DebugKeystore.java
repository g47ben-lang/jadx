package jadx.gui.rebuild;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates a throwaway, self-signed debug signing key (same purpose as Android's own
 * debug.keystore), using the JDK's own bundled {@code keytool} - avoids pulling in a separate
 * X.509-generation dependency just for this.
 */
public class DebugKeystore {
	private static final Logger LOG = LoggerFactory.getLogger(DebugKeystore.class);

	public static final String STORE_PASSWORD = "android";
	public static final String KEY_ALIAS = "jadxdebugkey";

	public static final class KeyMaterial {
		public final PrivateKey privateKey;
		public final X509Certificate certificate;

		public KeyMaterial(PrivateKey privateKey, X509Certificate certificate) {
			this.privateKey = privateKey;
			this.certificate = certificate;
		}
	}

	private DebugKeystore() {
	}

	/**
	 * Creates a fresh debug keystore file at {@code keystorePath} and returns its key material.
	 * Blocking call, must be run on a background thread.
	 */
	public static KeyMaterial generate(Path keystorePath) throws IOException, InterruptedException {
		List<String> command = List.of(
				keytoolBinary(),
				"-genkeypair",
				"-keystore", keystorePath.toString(),
				"-storepass", STORE_PASSWORD,
				"-alias", KEY_ALIAS,
				"-keypass", STORE_PASSWORD,
				"-keyalg", "RSA",
				"-keysize", "2048",
				"-validity", "10000",
				"-dname", "CN=jadx debug,O=jadx,C=US");
		LOG.debug("Generating debug keystore: {}", keystorePath);
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		Process process = pb.start();
		String output = readAll(process);
		boolean finished = process.waitFor(1, TimeUnit.MINUTES);
		if (!finished) {
			process.destroyForcibly();
			throw new IOException("keytool timed out");
		}
		if (process.exitValue() != 0) {
			throw new IOException("keytool failed: " + output);
		}
		return load(keystorePath);
	}

	private static KeyMaterial load(Path keystorePath) throws IOException {
		KeyStore ks;
		try {
			ks = KeyStore.getInstance("JKS");
			try (InputStream is = Files.newInputStream(keystorePath)) {
				ks.load(is, STORE_PASSWORD.toCharArray());
			}
			PrivateKey privateKey = (PrivateKey) ks.getKey(KEY_ALIAS, STORE_PASSWORD.toCharArray());
			X509Certificate certificate = (X509Certificate) ks.getCertificate(KEY_ALIAS);
			if (privateKey == null || certificate == null) {
				throw new IOException("Debug keystore is missing the expected key/certificate");
			}
			return new KeyMaterial(privateKey, certificate);
		} catch (Exception e) {
			throw new IOException("Failed to load generated debug keystore", e);
		}
	}

	private static String readAll(Process process) throws IOException {
		try (InputStream is = process.getInputStream()) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String keytoolBinary() {
		String javaHome = System.getProperty("java.home");
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		File exe = new File(javaHome, "bin/keytool" + (windows ? ".exe" : ""));
		return exe.exists() ? exe.getAbsolutePath() : "keytool";
	}
}
