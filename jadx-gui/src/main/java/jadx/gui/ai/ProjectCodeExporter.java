package jadx.gui.ai;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.gui.ui.MainWindow;

/**
 * Builds a combined text dump of the whole decompiled project - AndroidManifest.xml followed by
 * every class, each section labeled with its file path - so it can either be handed to the AI
 * Assistant as full project context in one go, or exported to disk for the user to upload to
 * another AI tool (Claude, ChatGPT, ...) themselves.
 */
public class ProjectCodeExporter {
	private static final Logger LOG = LoggerFactory.getLogger(ProjectCodeExporter.class);

	/**
	 * Target size per exported file, so parts stay uploadable to AI tools that cap file size
	 * (most sit somewhere around 20-30 MB per file). A single class is never split across two
	 * parts, so an unusually large class can still make one part exceed this.
	 */
	private static final int MAX_CHUNK_BYTES = 18 * 1024 * 1024;

	private final MainWindow mainWindow;

	public ProjectCodeExporter(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	/**
	 * Everything in one string, for the AI Assistant's own chat context (which applies its own,
	 * much smaller, size cap on top of this). Blocking call, must be run on a background thread.
	 */
	public String exportAll() {
		StringBuilder result = new StringBuilder();
		for (String section : collectSections()) {
			result.append(section);
		}
		return result.toString();
	}

	/**
	 * Same content as {@link #exportAll()}, split into ~{@link #MAX_CHUNK_BYTES}-sized parts (each
	 * one a self-contained, whole set of file sections - never splitting a class/resource section
	 * across two parts) so every part can actually be uploaded to an AI tool that caps file size.
	 * Blocking call, must be run on a background thread.
	 */
	public List<String> exportAllChunked() {
		return packIntoChunks(collectSections());
	}

	private List<String> collectSections() {
		List<String> sections = new ArrayList<>();
		addManifestSection(sections);
		addClassSections(sections);
		return sections;
	}

	private void addManifestSection(List<String> sections) {
		for (ResourceFile res : mainWindow.getWrapper().getResources()) {
			if (res.getType() == ResourceType.MANIFEST) {
				try {
					String content = res.loadContent().getText().getCodeStr();
					sections.add("=== " + res.getDeobfName() + " ===\n" + content + "\n\n");
				} catch (Exception e) {
					LOG.debug("Failed to load AndroidManifest.xml during full project export", e);
				}
				return;
			}
		}
	}

	private void addClassSections(List<String> sections) {
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
			sections.add("=== " + cls.getFullName().replace('.', '/') + ".java ===\n" + code + "\n\n");
		}
	}

	private static List<String> packIntoChunks(List<String> sections) {
		List<String> rawChunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		long currentBytes = 0;
		for (String section : sections) {
			int sectionBytes = section.getBytes(StandardCharsets.UTF_8).length;
			if (current.length() > 0 && currentBytes + sectionBytes > MAX_CHUNK_BYTES) {
				rawChunks.add(current.toString());
				current = new StringBuilder();
				currentBytes = 0;
			}
			current.append(section);
			currentBytes += sectionBytes;
		}
		if (current.length() > 0) {
			rawChunks.add(current.toString());
		}
		int total = rawChunks.size();
		if (total <= 1) {
			return rawChunks;
		}
		List<String> chunks = new ArrayList<>(total);
		for (int i = 0; i < total; i++) {
			chunks.add("(Part " + (i + 1) + " of " + total
					+ " - the project source code was too large for one file and was split)\n\n"
					+ rawChunks.get(i));
		}
		return chunks;
	}
}
