package jadx.gui.ai;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JavaClass;
import jadx.gui.ui.MainWindow;

/**
 * Simple, bounded text search over the decompiled project's classes, used to give the AI
 * Assistant a "search_code" tool so it can look at the actual project instead of only
 * answering from general knowledge.
 */
public class ProjectCodeSearch {
	private static final Logger LOG = LoggerFactory.getLogger(ProjectCodeSearch.class);

	private static final int MAX_SCANNED_CLASSES = 8000;
	private static final int CONTEXT_LINES_BEFORE = 3;
	private static final int CONTEXT_LINES_AFTER = 4;

	private final MainWindow mainWindow;

	public ProjectCodeSearch(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	/**
	 * Case-insensitive substring search across all decompiled classes.
	 * Returns up to {@code maxMatches} snippets, or a "no matches" message.
	 */
	public String search(String query, int maxMatches) {
		if (query == null || query.isBlank()) {
			return "Empty search query.";
		}
		List<JavaClass> classes = mainWindow.getWrapper().getIncludedClassesWithInners();
		String needle = query.toLowerCase();
		StringBuilder result = new StringBuilder();
		int matchCount = 0;
		int scanned = 0;
		for (JavaClass cls : classes) {
			if (matchCount >= maxMatches || scanned >= MAX_SCANNED_CLASSES) {
				break;
			}
			scanned++;
			String code;
			try {
				code = cls.getCode();
			} catch (Exception e) {
				LOG.debug("Failed to decompile class during AI code search: {}", cls, e);
				continue;
			}
			if (code == null) {
				continue;
			}
			int idx = code.toLowerCase().indexOf(needle);
			if (idx < 0) {
				continue;
			}
			matchCount++;
			appendSnippet(result, cls.getFullName(), code, idx);
		}
		if (matchCount == 0) {
			return "No matches found in the decompiled project for: " + query;
		}
		if (matchCount >= maxMatches) {
			result.append("(more matches exist, showing first ").append(maxMatches).append(")\n");
		}
		return result.toString();
	}

	private void appendSnippet(StringBuilder result, String className, String code, int matchIndex) {
		String[] lines = code.split("\n", -1);
		int matchLine = countLines(code, matchIndex);
		int start = Math.max(0, matchLine - CONTEXT_LINES_BEFORE);
		int end = Math.min(lines.length, matchLine + CONTEXT_LINES_AFTER + 1);
		result.append("=== ").append(className).append(" ===\n");
		for (int i = start; i < end; i++) {
			result.append(lines[i]).append('\n');
		}
		result.append('\n');
	}

	private static int countLines(String s, int upToIndex) {
		int lines = 0;
		for (int i = 0; i < upToIndex; i++) {
			if (s.charAt(i) == '\n') {
				lines++;
			}
		}
		return lines;
	}
}
