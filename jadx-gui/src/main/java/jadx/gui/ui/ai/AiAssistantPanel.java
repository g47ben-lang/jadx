package jadx.gui.ui.ai;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import jadx.gui.ai.AiChatMessage;
import jadx.gui.ai.AiClient;
import jadx.gui.ai.AiSettings;
import jadx.gui.ai.ProjectCodeSearch;
import jadx.gui.ui.MainWindow;
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

	private final MainWindow mainWindow;
	private final List<AiChatMessage> history = new ArrayList<>();
	private final StringBuilder chatHtmlBody = new StringBuilder();

	private JEditorPane chatPane;
	private JTextArea inputArea;
	private JButton sendBtn;

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

		JPanel buttonsPanel = new JPanel();
		buttonsPanel.add(connectBtn);
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
		chatHtmlBody.setLength(0);
		refreshChatPane();
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
				AiClient client = new AiClient(settings);
				List<AiChatMessage> request = new ArrayList<>();
				request.add(new AiChatMessage(AiChatMessage.ROLE_SYSTEM,
						"You are an assistant embedded in the jadx Android decompiler GUI, helping the user "
								+ "understand a specific decompiled Android app. You have a search_code tool that "
								+ "searches the actual decompiled source of the app currently open in jadx - use it "
								+ "whenever the question is about what this particular app does, rather than "
								+ "answering only from general Android knowledge. Be concise."));
				request.addAll(history);
				String reply = client.askWithTools(request, new ProjectCodeSearch(mainWindow));
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

	private void setBusy(boolean busy) {
		sendBtn.setEnabled(!busy);
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
		for (String line : text.split("\n", -1)) {
			if (line.strip().startsWith("```")) {
				if (!inCodeBlock) {
					inCodeBlock = true;
					html.append("<pre style=\"direction:ltr; text-align:left; background:#00000012; "
							+ "padding:6px; white-space:pre-wrap; font-family:monospace;\">");
				} else {
					inCodeBlock = false;
					html.append("</pre>");
				}
				continue;
			}
			if (inCodeBlock) {
				html.append(escapeHtml(line)).append('\n');
			} else {
				html.append(formatInline(escapeHtml(line))).append("<br>");
			}
		}
		return html.toString();
	}

	private static String formatInline(String escapedLine) {
		String result = escapedLine.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
		return result.replaceAll("`([^`]+?)`", "<code style=\"direction:ltr; unicode-bidi:embed;\">$1</code>");
	}

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
