package jadx.gui.rebuild;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import com.android.apksig.ApkSigner;

/**
 * Signs an (unsigned) rebuilt APK with a throwaway debug key (see {@link DebugKeystore}), using
 * the apksig library jadx-gui already depends on (the same library backing the official
 * {@code apksigner} tool and the "APK signature" verification tab).
 */
public class ApkResigner {
	private ApkResigner() {
	}

	/** Blocking call, must be run on a background thread. */
	public static void sign(DebugKeystore.KeyMaterial key, File inputApk, File outputApk) throws IOException {
		ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
				DebugKeystore.KEY_ALIAS, key.privateKey, List.of(key.certificate)).build();
		ApkSigner signer = new ApkSigner.Builder(List.of(signerConfig))
				.setInputApk(inputApk)
				.setOutputApk(outputApk)
				.setV1SigningEnabled(true)
				.setV2SigningEnabled(true)
				.setV3SigningEnabled(true)
				.setV4SigningEnabled(false)
				.build();
		try {
			signer.sign();
		} catch (IOException e) {
			throw e;
		} catch (GeneralSecurityException | RuntimeException e) {
			throw new IOException("APK signing failed: " + e.getMessage(), e);
		}
	}
}
