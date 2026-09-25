package jadx.gui.ui.rebuild;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import org.jetbrains.annotations.Nullable;

import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.gui.rebuild.ApkResigner;
import jadx.gui.rebuild.ApktoolProcess;
import jadx.gui.rebuild.DebugKeystore;
import jadx.gui.rebuild.RebuildSettings;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.filedialog.FileDialogWrapper;
import jadx.gui.ui.filedialog.FileOpenMode;
import jadx.gui.utils.NLS;
import jadx.gui.utils.ui.DocumentUpdateListener;
import jadx.zip.IZipEntry;

/**
 * Manual, AI-free first step of the edit-and-rebuild pipeline: decode the currently open APK with
 * Apktool into editable smali + resources, let the user edit that folder in whatever editor they
 * like, then rebuild and sign it back into an installable APK. Apktool itself is not bundled with
 * jadx (see {@link RebuildSettings}).
 */
public class RebuildApkPanel extends JPanel {
	private static final long serialVersionUID = 1L;

	private final MainWindow mainWindow;

	private JTextField apktoolPathFld;
	private JTextField apkPathFld;
	private JTextField decodedDirFld;
	private JTextField outputApkFld;
	private JTextArea logArea;
	private JButton decodeBtn;
	private JButton buildBtn;

	public RebuildApkPanel(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
		initUI();
	}

	private void initUI() {
		RebuildSettings rebuildSettings = mainWindow.getSettings().getRebuildSettings();

		apktoolPathFld = new JTextField(rebuildSettings.getApktoolJarPath(), 30);
		apktoolPathFld.getDocument().addDocumentListener(
				new DocumentUpdateListener(ev -> rebuildSettings.setApktoolJarPath(apktoolPathFld.getText())));
		JButton apktoolBrowseBtn = new JButton(NLS.str("rebuild.browse"));
		apktoolBrowseBtn.addActionListener(
				ev -> browseForFile(apktoolPathFld, List.of("jar"), JFileChooser.FILES_ONLY, true));

		File currentApk = findCurrentApkFile();
		apkPathFld = new JTextField(currentApk != null ? currentApk.getAbsolutePath() : "", 30);
		JButton apkBrowseBtn = new JButton(NLS.str("rebuild.browse"));
		apkBrowseBtn.addActionListener(ev -> browseForFile(apkPathFld, List.of("apk"), JFileChooser.FILES_ONLY, true));

		String defaultDecodedDir = currentApk != null ? currentApk.getAbsolutePath() + "_decoded" : "";
		decodedDirFld = new JTextField(defaultDecodedDir, 30);
		JButton decodedDirBrowseBtn = new JButton(NLS.str("rebuild.browse"));
		decodedDirBrowseBtn.addActionListener(
				ev -> browseForFile(decodedDirFld, List.of(), JFileChooser.DIRECTORIES_ONLY, true));

		String defaultOutputApk = currentApk != null
				? currentApk.getAbsolutePath().replaceAll("\\.apk$", "") + "_rebuilt.apk"
				: "";
		outputApkFld = new JTextField(defaultOutputApk, 30);
		JButton outputApkBrowseBtn = new JButton(NLS.str("rebuild.browse"));
		outputApkBrowseBtn.addActionListener(
				ev -> browseForFile(outputApkFld, List.of("apk"), JFileChooser.FILES_ONLY, false));

		decodeBtn = new JButton(NLS.str("rebuild.decode"));
		decodeBtn.addActionListener(ev -> decode());
		buildBtn = new JButton(NLS.str("rebuild.build_and_sign"));
		buildBtn.addActionListener(ev -> buildAndSign());

		logArea = new JTextArea();
		logArea.setEditable(false);
		logArea.setLineWrap(true);
		logArea.setWrapStyleWord(true);
		JScrollPane logScroll = new JScrollPane(logArea);
		logScroll.setPreferredSize(new Dimension(600, 300));

		JPanel form = new JPanel();
		form.setLayout(new BoxLayout(form, BoxLayout.PAGE_AXIS));
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		form.add(labeledRow(NLS.str("rebuild.apktool_path"), apktoolPathFld, apktoolBrowseBtn));
		form.add(labeledRow(NLS.str("rebuild.source_apk"), apkPathFld, apkBrowseBtn));
		form.add(labeledRow(NLS.str("rebuild.decoded_dir"), decodedDirFld, decodedDirBrowseBtn));
		form.add(labeledRow(NLS.str("rebuild.output_apk"), outputApkFld, outputApkBrowseBtn));

		JTextArea helpText = new JTextArea(NLS.str("rebuild.help_text"));
		helpText.setEditable(false);
		helpText.setLineWrap(true);
		helpText.setWrapStyleWord(true);
		helpText.setOpaque(false);
		helpText.setFocusable(false);
		helpText.setPreferredSize(new Dimension(600, 70));
		form.add(helpText);

		JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		buttonsPanel.add(decodeBtn);
		buttonsPanel.add(buildBtn);
		form.add(buttonsPanel);

		setLayout(new BorderLayout(5, 5));
		add(form, BorderLayout.PAGE_START);
		add(logScroll, BorderLayout.CENTER);
	}

	private static JPanel labeledRow(String label, JTextField field, JButton browseBtn) {
		JPanel row = new JPanel(new BorderLayout(5, 2));
		row.add(new JLabel(label), BorderLayout.PAGE_START);
		JPanel fieldRow = new JPanel(new BorderLayout(5, 0));
		fieldRow.add(field, BorderLayout.CENTER);
		fieldRow.add(browseBtn, BorderLayout.LINE_END);
		row.add(fieldRow, BorderLayout.CENTER);
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
		return row;
	}

	private void browseForFile(JTextField target, List<String> exts, int selectionMode, boolean openExisting) {
		FileDialogWrapper fileDialog =
				new FileDialogWrapper(mainWindow, openExisting ? FileOpenMode.CUSTOM_OPEN : FileOpenMode.CUSTOM_SAVE);
		fileDialog.setSelectionMode(selectionMode);
		if (!exts.isEmpty()) {
			fileDialog.setFileExtList(exts);
		}
		List<Path> paths = fileDialog.show();
		if (paths.size() == 1) {
			target.setText(paths.get(0).toAbsolutePath().toString());
		}
	}

	private File findCurrentApkFile() {
		for (ResourceFile res : mainWindow.getWrapper().getResources()) {
			if (res.getType() == ResourceType.MANIFEST) {
				IZipEntry zipEntry = res.getZipEntry();
				if (zipEntry != null && zipEntry.getZipFile() != null) {
					return zipEntry.getZipFile();
				}
			}
		}
		return null;
	}

	private void decode() {
		Path apktoolJar = Path.of(apktoolPathFld.getText().trim());
		Path apkFile = Path.of(apkPathFld.getText().trim());
		Path outputDir = Path.of(decodedDirFld.getText().trim());
		if (!validateApktoolPath(apktoolJar) || !validate(apkFile, "rebuild.error.no_apk")) {
			return;
		}
		setBusy(true);
		log(NLS.str("rebuild.log.decoding_start", apkFile.toString(), outputDir.toString()));
		AtomicReference<ApktoolProcess.Result> resultRef = new AtomicReference<>();
		AtomicReference<Throwable> errorRef = new AtomicReference<>();
		mainWindow.getBackgroundExecutor().execute(NLS.str("rebuild.decoding"), () -> {
			try {
				resultRef.set(new ApktoolProcess(apktoolJar).decode(apkFile, outputDir));
			} catch (Throwable e) {
				errorRef.set(e);
			}
		}, status -> {
			setBusy(false);
			if (errorRef.get() != null) {
				log(NLS.str("rebuild.log.failed", String.valueOf(errorRef.get())));
			} else {
				ApktoolProcess.Result result = resultRef.get();
				log(result.output);
				log(NLS.str(result.success ? "rebuild.log.decode_success" : "rebuild.log.decode_failed"));
			}
		});
	}

	private void buildAndSign() {
		Path apktoolJar = Path.of(apktoolPathFld.getText().trim());
		Path decodedDir = Path.of(decodedDirFld.getText().trim());
		Path outputApk = Path.of(outputApkFld.getText().trim());
		if (!validateApktoolPath(apktoolJar) || !validate(decodedDir, "rebuild.error.no_decoded_dir")) {
			return;
		}
		setBusy(true);
		log(NLS.str("rebuild.log.build_start", decodedDir.toString(), outputApk.toString()));
		AtomicReference<String> errorRef = new AtomicReference<>();
		mainWindow.getBackgroundExecutor().execute(NLS.str("rebuild.building"), () -> {
			Path tmpDir = null;
			try {
				tmpDir = Files.createTempDirectory("jadx-rebuild");
				Path unsignedApk = tmpDir.resolve("unsigned.apk");
				ApktoolProcess.Result buildResult = new ApktoolProcess(apktoolJar).build(decodedDir, unsignedApk);
				log(buildResult.output);
				if (!buildResult.success) {
					errorRef.set(NLS.str("rebuild.log.apktool_build_failed"));
					return;
				}
				log(NLS.str("rebuild.log.signing_start"));
				Path keystorePath = tmpDir.resolve("debug.keystore");
				DebugKeystore.KeyMaterial key = DebugKeystore.generate(keystorePath);
				ApkResigner.sign(key, unsignedApk.toFile(), outputApk.toFile());
				log(NLS.str("rebuild.log.signed_output", outputApk.toString()));
			} catch (Throwable e) {
				errorRef.set(e.getMessage() != null ? e.getMessage() : e.toString());
			} finally {
				deleteRecursively(tmpDir);
			}
		}, status -> {
			setBusy(false);
			if (errorRef.get() != null) {
				log(NLS.str("rebuild.log.failed", errorRef.get()));
			} else {
				log(NLS.str("rebuild.log.done"));
			}
		});
	}

	private static void deleteRecursively(@Nullable Path dir) {
		if (dir == null) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	private boolean validateApktoolPath(Path apktoolJar) {
		return validate(apktoolJar, "rebuild.error.no_apktool");
	}

	private boolean validate(Path path, String messageKey) {
		if (path.toString().isBlank() || !Files.exists(path)) {
			JOptionPane.showMessageDialog(this, NLS.str(messageKey), NLS.str("rebuild.title"), JOptionPane.WARNING_MESSAGE);
			return false;
		}
		return true;
	}

	private void setBusy(boolean busy) {
		decodeBtn.setEnabled(!busy);
		buildBtn.setEnabled(!busy);
	}

	private void log(String text) {
		logArea.append(text);
		logArea.append("\n");
		logArea.setCaretPosition(logArea.getDocument().getLength());
	}
}
