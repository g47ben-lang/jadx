package jadx.gui.ui.rebuild;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;

import org.jetbrains.annotations.Nullable;

import jadx.gui.settings.JadxSettings;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.NLS;
import jadx.gui.utils.UiUtils;

/**
 * Standalone window hosting the {@link RebuildApkPanel}. Only one instance is kept open at a time.
 */
public class RebuildApkDialog extends JFrame {
	private static final long serialVersionUID = 1L;

	private static RebuildApkDialog openDialog;

	private final transient JadxSettings settings;

	public static RebuildApkDialog open(MainWindow mainWindow) {
		RebuildApkDialog dialog;
		if (openDialog != null) {
			dialog = openDialog;
		} else {
			dialog = new RebuildApkDialog(mainWindow);
			openDialog = dialog;
		}
		dialog.setVisible(true);
		dialog.toFront();
		return dialog;
	}

	public static @Nullable RebuildApkDialog getOpenDialog() {
		return openDialog;
	}

	private RebuildApkDialog(MainWindow mainWindow) {
		settings = mainWindow.getSettings();
		UiUtils.setWindowIcons(this);

		RebuildApkPanel panel = new RebuildApkPanel(mainWindow);
		Container contentPane = getContentPane();
		contentPane.add(panel, BorderLayout.CENTER);

		setTitle(NLS.str("rebuild.title"));
		pack();
		setSize(700, 600);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setLocationRelativeTo(null);
		settings.loadWindowPos(this);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				openDialog = null;
			}
		});
	}

	@Override
	public void dispose() {
		settings.saveWindowPos(this);
		super.dispose();
	}
}
