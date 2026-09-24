package jadx.gui.ui.ai;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import jadx.gui.ai.AiChatMessage;
import jadx.gui.ai.AiClient;
import jadx.gui.ai.AiSettings;
import jadx.gui.ai.ProjectCodeExporter;
import jadx.gui.ai.ProjectCodeSearch;
import jadx.gui.ai.ProjectFileOpener;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.filedialog.FileDialogWrapper;
import jadx.gui.ui.filedialog.FileOpenMode;
import jadx.gui.utils.NLS;

/**
 * Simple chat panel talking to a configured AI provider (see {@link AiSettings}).
 * The whole conversation is kept in memory only, nothing is persisted.
 */
public class AiAssistantPanel extends JPanel {
	private static final long serialVersionUID = 1L;

	private static final String USER_BG = "#DCEFFF";
	private static final String USER_BORDER = "#4A90D9";
	private static final String ASSISTANT_BG = "#F0F0F0";
	private static final String ASSISTANT_BORDER = "#8A8A8A";
	private static final String ERROR_BG = "#FBE1E1";
	private static final String ERROR_BORDER = "#C0392B";

	/**
	 * Safety cap (characters, roughly 4 chars/token) on how much of the project code dump gets
	 * attached to the chat automatically. Past this the dump is truncated with a notice - the AI
	 * still has search_code/open_file to look at anything that got cut. The full, untruncated dump
	 * is always available via the manual "Export all code" button regardless of this limit.
	 */
	private static final int MAX_AUTO_CONTEXT_CHARS = 300_000;

	private final MainWindow mainWindow;
	private final List<AiChatMessage> history = new ArrayList<>();
	private final StringBuilder chatHtmlBody = new StringBuilder();
	private boolean codeDumpAttached = false;

	private JEditorPane chatPane;
	private JTextArea inputArea;
	private JButton sendBtn;
	private JButton exportBtn;

	public AiAssistantPanel(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
		initUI();
		if (!mainWindow.getSettings().getAiSettings().isEnabled()) {
			appendBlock(NLS.str("ai_assistant.welcome_not_enabled"), ASSISTANT_BG, ASSISTANT_BORDER, null);
		}
	}

	private void initUI() {
		chatPane = new JEditorPane();
		chatPane.setContentType("text/html");
		chatPane.setEditable(false);
		chatPane.setBackground(Color.WHITE);
		refreshChatPane();
		JScrollPane chatScroll = new JScrollPane(chatPane);
		chatScroll.setPreferredSize(new Dimension(500, 400));

		inputArea = new JTextArea(4, 40);
		inputArea.setLineWrap(true);
		inputArea.setWrapStyleWord(true);
		inputArea.addKeyListener(new KeyListener() {
			@Override
			public void keyTyped(KeyEvent e) {
			}

			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
					e.consume();
					send();
				}
			}

			@Override
			public void keyReleased(KeyEvent e) {
			}
		});
		JScrollPane inputScroll = new JScrollPane(inputArea);

		sendBtn = new JButton(NLS.str("ai_assistant.send"));
		sendBtn.addActionListener(ev -> send());
		JButton clearBtn = new JButton(NLS.str("ai_assistant.clear"));
		clearBtn.addActionListener(ev -> clear());
		JButton connectBtn = new JButton(NLS.str("ai_assistant.connect_provider"));
		connectBtn.addActionListener(ev -> mainWindow.openSettings(NLS.str("preferences.ai")));
		exportBtn = new JButton(NLS.str("ai_assistant.export_code"));
		exportBtn.addActionListener(ev -> exportProjectCode());

		JPanel buttonsPanel = new JPanel();
		buttonsPanel.add(connectBtn);
		buttonsPanel.add(exportBtn);
		buttonsPanel.add(clearBtn);
		buttonsPanel.add(sendBtn);

		JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
		bottomPanel.add(inputScroll, BorderLayout.CENTER);
		bottomPanel.add(buttonsPanel, BorderLayout.PAGE_END);

		setLayout(new BorderLayout(5, 5));
		add(chatScroll, BorderLayout.CENTER);
		add(bottomPanel, BorderLayout.PAGE_END);
	}

	public void clear() {
		history.clear();
		codeDumpAttached = false;
		chatHtmlBody.setLength(0);
		refreshChatPane();
	}

	/**
	 * Builds the full project code dump (AndroidManifest.xml + every decompiled class), split into
	 * ~18 MB parts, and lets the user save them into a folder, to upload to an external AI tool
	 * (Claude, ChatGPT, ...) themselves - independent of whether the AI Assistant itself is
	 * configured/enabled. Split into multiple files since a single dump can easily reach tens of
	 * MB for a real app, well past what most AI tools accept as one upload.
	 */
	private void exportProjectCode() {
		setBusy(true);
		AtomicReference<List<String>> chunks = new AtomicReference<>();
		AtomicReference<String> error = new AtomicReference<>();
		mainWindow.getBackgroundExecutor().execute(NLS.str("ai_assistant.exporting"), () -> {
			try {
				chunks.set(new ProjectCodeExporter(mainWindow).exportAllChunked());
			} catch (Throwable e) {
				error.set(e.getMessage() != null ? e.getMessage() : e.toString());
			}
		}, status -> {
			setBusy(false);
			if (error.get() != null) {
				JOptionPane.showMessageDialog(this, error.get(),
						NLS.str("ai_assistant.export_code.failed"), JOptionPane.ERROR_MESSAGE);
			} else {
				saveExportedCode(chunks.get());
			}
		});
	}

	private void saveExportedCode(List<String> chunks) {
		FileDialogWrapper fileDialog = new FileDialogWrapper(mainWindow, FileOpenMode.CUSTOM_SAVE);
		fileDialog.setTitle(NLS.str("ai_assistant.export_code.select_folder"));
		fileDialog.setSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		List<Path> paths = fileDialog.show();
		if (paths.size() != 1) {
			return;
		}
		Path dir = paths.get(0);
		try {
			Files.createDirectories(dir);
			boolean multiPart = chunks.size() > 1;
			for (int i = 0; i < chunks.size(); i++) {
				String fileName = multiPart ? "project_code_part" + (i + 1) + ".txt" : "project_code.txt";
				Files.writeString(dir.resolve(fileName), chunks.get(i), StandardCharsets.UTF_8);
			}
			JOptionPane.showMessageDialog(this,
					NLS.str("ai_assistant.export_code.success", dir.toString(), String.valueOf(chunks.size())),
					NLS.str("ai_assistant.export_code"), JOptionPane.INFORMATION_MESSAGE);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, e.getMessage(),
					NLS.str("ai_assistant.export_code.failed"), JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Pre-fills the input with a question about the given code snippet and sends it right away.
	 */
	public void askAboutCode(String question, String code) {
		String fullQuestion = question + "\n\n```\n" + code + "\n```";
		inputArea.setText(fullQuestion);
		send();
	}

	private void send() {
		String text = inputArea.getText().trim();
		if (text.isEmpty()) {
			return;
		}
		AiSettings settings = mainWindow.getSettings().getAiSettings();
		if (!settings.isEnabled()) {
			JOptionPane.showMessageDialog(this, NLS.str("ai_assistant.not_enabled"),
					NLS.str("preferences.ai"), JOptionPane.WARNING_MESSAGE);
			return;
		}
		inputArea.setText("");
		appendBlock(text, USER_BG, USER_BORDER, NLS.str("ai_assistant.you"));
		history.add(new AiChatMessage(AiChatMessage.ROLE_USER, text));
		setBusy(true);

		AtomicReference<String> resultText = new AtomicReference<>();
		AtomicReference<Boolean> success = new AtomicReference<>(false);
		mainWindow.getBackgroundExecutor().execute(NLS.str("ai_assistant.thinking"), () -> {
			try {
				attachCodeDumpIfNeeded();
				List<AiChatMessage> request = new ArrayList<>();
				request.add(new AiChatMessage(AiChatMessage.ROLE_SYSTEM,
						"You are an assistant embedded in the jadx Android decompiler GUI, helping the user "
								+ "understand a specific decompiled Android app. The full source code of the project "
								+ "currently open in jadx (AndroidManifest.xml and every decompiled class, labeled by "
								+ "file path) has already been given to you as an earlier message in this "
								+ "conversation - refer to it directly instead of guessing from general Android "
								+ "knowledge or asking the user to paste code. You also have a search_code tool to "
								+ "search that same source again if useful, and an open_file tool that opens a class "
								+ "or resource for the user directly in jadx - use it when the user would benefit "
								+ "from looking at a file you found themselves. Be concise."));
				request.addAll(history);
				String reply = AiClient.askWithToolsAndFailover(settings, request,
						new ProjectCodeSearch(mainWindow), new ProjectFileOpener(mainWindow));
				resultText.set(reply);
				success.set(true);
			} catch (Throwable e) {
				resultText.set(e.getMessage() != null ? e.getMessage() : e.toString());
				success.set(false);
			}
		}, status -> {
			setBusy(false);
			if (Boolean.TRUE.equals(success.get())) {
				String reply = resultText.get();
				history.add(new AiChatMessage(AiChatMessage.ROLE_ASSISTANT, reply));
				appendBlock(reply, ASSISTANT_BG, ASSISTANT_BORDER, NLS.str("ai_assistant.assistant"));
			} else {
				appendBlock(resultText.get(), ERROR_BG, ERROR_BORDER, NLS.str("ai_assistant.error"));
			}
		});
	}

	/**
	 * Builds the full project code dump once per chat session (until {@link #clear()}) and
	 * inserts it as the first message in {@code history}, before the very first user question,
	 * so every request from then on sends it as part of the conversation automatically.
	 * Must be called from the background thread that's about to send a request - decompiling the
	 * whole project can take a while.
	 */
	private void attachCodeDumpIfNeeded() {
		if (codeDumpAttached) {
			return;
		}
		String dump = new ProjectCodeExporter(mainWindow).exportAll();
		if (dump.length() > MAX_AUTO_CONTEXT_CHARS) {
			dump = dump.substring(0, MAX_AUTO_CONTEXT_CHARS)
					+ "\n\n... (truncated, the project is too large to include in full here - "
					+ "use the search_code/open_file tools for anything not shown above)";
		}
		history.add(0, new AiChatMessage(AiChatMessage.ROLE_USER,
				"Here is the full source code of the Android project currently open in jadx, "
						+ "organized by file path:\n\n" + dump));
		codeDumpAttached = true;
	}

	private void setBusy(boolean busy) {
		sendBtn.setEnabled(!busy);
		exportBtn.setEnabled(!busy);
		inputArea.setEnabled(!busy);
	}

	/**
	 * Appends one chat bubble (right-aligned, RTL-aware) with a bold role label and
	 * lightweight markdown rendering (bold, inline code, fenced code blocks).
	 */
	private void appendBlock(String text, String background, String borderColor, String label) {
		chatHtmlBody.append("<div style=\"direction:rtl; text-align:right; background:")
				.append(background)
				.append("; border-right:4px solid ")
				.append(borderColor)
				.append("; margin:8px 4px; padding:8px 12px;\">");
		if (label != null) {
			chatHtmlBody.append("<b>").append(escapeHtml(label)).append("</b><br>");
		}
		chatHtmlBody.append(formatMessageHtml(text));
		chatHtmlBody.append("</div>");
		refreshChatPane();
	}

	private void refreshChatPane() {
		String html = "<html><body style=\"direction:rtl; font-family:sans-serif; font-size:12px; margin:0;\">"
				+ chatHtmlBody
				+ "</body></html>";
		chatPane.setText(html);
		SwingUtilities.invokeLater(() -> chatPane.setCaretPosition(chatPane.getDocument().getLength()));
	}

	private static String formatMessageHtml(String text) {
		StringBuilder html = new StringBuilder();
		boolean inCodeBlock = false;
		boolean inList = false;
		for (String line : text.split("\n", -1)) {
			String trimmed = line.strip();
			if (trimmed.startsWith("```")) {
				inList = closeList(html, inList);
				if (!inCodeBlock) {
					inCodeBlock = true;
					html.append("<pre style=\"direction:ltr; text-align:left; background:#E4E4E4; color:#000000; "
							+ "padding:6px; white-space:pre-wrap; font-family:monospace;\">");
				} else {
					inCodeBlock = false;
					html.append("</pre>");
				}
				continue;
			}
			if (inCodeBlock) {
				html.append(escapeHtml(line)).append('\n');
				continue;
			}
			if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
				inList = closeList(html, inList);
				html.append("<hr>");
				continue;
			}
			int headerLevel = 0;
			while (headerLevel < trimmed.length() && trimmed.charAt(headerLevel) == '#') {
				headerLevel++;
			}
			if (headerLevel > 0 && headerLevel <= 6 && trimmed.length() > headerLevel && trimmed.charAt(headerLevel) == ' ') {
				inList = closeList(html, inList);
				String headerText = trimmed.substring(headerLevel + 1).strip();
				html.append("<b style=\"font-size:").append(Math.max(100, 130 - headerLevel * 8)).append("%;\">")
						.append(formatInline(escapeHtml(headerText)))
						.append("</b><br>");
				continue;
			}
			if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
				if (!inList) {
					html.append("<ul style=\"margin:2px 0;\">");
					inList = true;
				}
				html.append("<li>").append(formatInline(escapeHtml(trimmed.substring(2)))).append("</li>");
				continue;
			}
			inList = closeList(html, inList);
			html.append(formatInline(escapeHtml(line))).append("<br>");
		}
		closeList(html, inList);
		if (inCodeBlock) {
			html.append("</pre>");
		}
		return html.toString();
	}

	private static boolean closeList(StringBuilder html, boolean inList) {
		if (inList) {
			html.append("</ul>");
		}
		return false;
	}

	private static String formatInline(String escapedLine) {
		String result = escapedLine.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
		return result.replaceAll("`([^`]+?)`", "<code style=\"direction:ltr; unicode-bidi:embed;\">$1</code>");
	}

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
