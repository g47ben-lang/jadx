package jadx.gui.ai;

import java.util.List;

import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import jadx.gui.JadxWrapper;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.JResource;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.UiUtils;

/**
 * Lets the AI Assistant open a class or resource it found directly in jadx's own tab view.
 * Decompiled content usually only exists in memory/cache, not as a real file on disk, so
 * launching an external editor (Notepad, ...) on it generally isn't possible - opening it in
 * jadx itself is both more reliable and gives the user syntax highlighting and navigation.
 */
public class ProjectFileOpener {
	private final MainWindow mainWindow;

	public ProjectFileOpener(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	/**
	 * Case-insensitive lookup by class name (full or simple) or resource file name, then opens
	 * the first match in a jadx tab. Returns a short status string for the AI to report back.
	 */
	public String open(String name) {
		if (name == null || name.isBlank()) {
			return "No file name given.";
		}
		String needle = name.trim().toLowerCase();
		JadxWrapper wrapper = mainWindow.getWrapper();

		for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
			String fullName = cls.getFullName().toLowerCase();
			if (fullName.equals(needle) || fullName.endsWith("." + needle) || fullName.contains(needle)) {
				JNode node = mainWindow.getCacheObject().getNodeCache().makeFrom(cls);
				openNode(node);
				return "Opened class " + cls.getFullName() + " in jadx.";
			}
		}

		for (ResourceFile res : wrapper.getResources()) {
			String resName = res.getDeobfName();
			if (resName != null && resName.toLowerCase().contains(needle)) {
				openNode(new JResource(res, res.getDeobfName(), JResource.JResType.FILE));
				return "Opened resource " + resName + " in jadx.";
			}
		}
		return "Could not find a class or resource matching: " + name;
	}

	private void openNode(JNode node) {
		UiUtils.uiRun(() -> mainWindow.getTabsController().codeJump(node));
	}
}
