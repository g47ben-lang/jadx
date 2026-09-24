package jadx.gui.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.gui.ui.MainWindow;

/**
 * Builds one combined text dump of the whole decompiled project - AndroidManifest.xml followed by
 * every class, each section labeled with its file path - so it can either be handed to the AI
 * Assistant as full project context in one go, or exported to disk for the user to paste into
 * another AI tool (Claude, ChatGPT, ...) themselves.
 */
public class ProjectCodeExporter {
	private static final Logger LOG = LoggerFactory.getLogger(ProjectCodeExporter.class);

	private final MainWindow mainWindow;

	public ProjectCodeExporter(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	/**
	 * Blocking call (decompiles every class that isn't already cached), must be run on a
	 * background thread.
	 */
	public String exportAll() {
		StringBuilder result = new StringBuilder();
		appendManifest(result);
		for (JavaClass cls : mainWindow.getWrapper().getIncludedClassesWithInners()) {
			String code;
			try {
				code = cls.getCode();
			} catch (Exception e) {
				LOG.debug("Failed to decompile class during full project export: {}", cls, e);
				continue;
			}
			if (code == null) {
				continue;
			}
			result.append("=== ").append(cls.getFullName().replace('.', '/')).append(".java ===\n")
					.append(code)
					.append("\n\n");
		}
		return result.toString();
	}

	private void appendManifest(StringBuilder result) {
		for (ResourceFile res : mainWindow.getWrapper().getResources()) {
			if (res.getType() == ResourceType.MANIFEST) {
				try {
					String content = res.loadContent().getText().getCodeStr();
					result.append("=== ").append(res.getDeobfName()).append(" ===\n")
							.append(content)
							.append("\n\n");
				} catch (Exception e) {
					LOG.debug("Failed to load AndroidManifest.xml during full project export", e);
				}
				return;
			}
		}
	}
}
