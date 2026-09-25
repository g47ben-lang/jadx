package jadx.gui.rebuild;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Apktool (<a href="https://github.com/iBotPeaches/Apktool">github.com/iBotPeaches/Apktool</a>,
 * Apache-2.0) as a subprocess to decode an APK into editable smali + resources, and to rebuild an
 * edited copy of that folder back into an APK. Apktool is not bundled with jadx - the user points
 * this at a local apktool.jar (see {@link RebuildSettings}), same as every other GUI wrapper around
 * it (e.g. apk-editor-studio) does.
 */
public class ApktoolProcess {
	private static final Logger LOG = LoggerFactory.getLogger(ApktoolProcess.class);

	public static final class Result {
		public final boolean success;
		public final String output;

		public Result(boolean success, String output) {
			this.success = success;
			this.output = output;
		}
	}

	private final Path apktoolJar;

	public ApktoolProcess(Path apktoolJar) {
		this.apktoolJar = apktoolJar;
	}

	/**
	 * Decodes an APK into {@code outputDir} (smali sources + readable resources + AndroidManifest.xml).
	 * Blocking call, must be run on a background thread.
	 */
	public Result decode(Path apkFile, Path outputDir) throws IOException, InterruptedException {
		return run("decode", apkFile.toString(), "--output", outputDir.toString(), "--force");
	}

	/**
	 * Rebuilds a decoded (and possibly edited) folder back into an (unsigned) APK.
	 * Blocking call, must be run on a background thread.
	 */
	public Result build(Path decodedDir, Path outputApk) throws IOException, InterruptedException {
		return run("build", decodedDir.toString(), "--output", outputApk.toString(), "--force");
	}

	private Result run(String... apktoolArgs) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(javaBinary());
		command.add("-jar");
		command.add(apktoolJar.toString());
		command.addAll(List.of(apktoolArgs));

		LOG.debug("Running apktool: {}", command);
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		Process process = pb.start();
		StringBuilder output = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (output.length() > 0) {
					output.append('\n');
				}
				output.append(line);
			}
		}
		boolean finished = process.waitFor(10, TimeUnit.MINUTES);
		if (!finished) {
			process.destroyForcibly();
			return new Result(false, output + "\n(timed out after 10 minutes)");
		}
		return new Result(process.exitValue() == 0, output.toString());
	}

	private static String javaBinary() {
		String javaHome = System.getProperty("java.home");
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		File exe = new File(javaHome, "bin/java" + (windows ? ".exe" : ""));
		return exe.exists() ? exe.getAbsolutePath() : "java";
	}
}
