package io.rejar.ui;

import java.util.List;
import java.util.Set;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

/** Side by side comparison of two sources with changed lines highlighted. */
final class CompareTab extends Tab {

    private static final long MAX_CELLS = 6_000_000L;

    CompareTab(String title, String left, String right, String leftTitle, String rightTitle) {
        setText("⇄ " + title);
        CodeEditor leftEditor = new CodeEditor(Highlighter.Language.JAVA);
        CodeEditor rightEditor = new CodeEditor(Highlighter.Language.JAVA);
        leftEditor.setText(left == null ? "" : left);
        rightEditor.setText(right == null ? "" : right);

        List<String> a = List.of((left == null ? "" : left).split("\n", -1));
        List<String> b = List.of((right == null ? "" : right).split("\n", -1));
        boolean[][] changed = diff(a, b);
        int changes = 0;
        for (boolean c : changed[1]) {
            if (c) {
                changes++;
            }
        }
        int removed = 0;
        for (boolean c : changed[0]) {
            if (c) {
                removed++;
            }
        }
        Platform.runLater(() -> {
            mark(leftEditor, changed[0], "diff-removed");
            mark(rightEditor, changed[1], "diff-added");
        });

        SplitPane split = new SplitPane(pane(leftTitle, leftEditor), pane(rightTitle, rightEditor));
        split.setDividerPositions(0.5);
        Label summary = new Label(removed + " line(s) removed/changed on the left, " + changes
                + " line(s) added/changed on the right");
        summary.setPadding(new Insets(4, 8, 4, 8));
        summary.getStyleClass().add("editor-info");
        setContent(new BorderPane(split, summary, null, null, null));
    }

    private static BorderPane pane(String title, CodeEditor editor) {
        Label label = new Label(title);
        label.getStyleClass().add("section-title");
        label.setPadding(new Insets(3, 6, 3, 6));
        return new BorderPane(editor, new VBox(label), null, null, null);
    }

    private static void mark(CodeEditor editor, boolean[] lines, String style) {
        int count = Math.min(lines.length, editor.area().getParagraphs().size());
        for (int i = 0; i < count; i++) {
            if (lines[i]) {
                editor.area().setParagraphStyle(i, Set.of(style));
            }
        }
    }

    /** Line diff (LCS on the part between common prefix and suffix). Returns changed flags for a and b. */
    static boolean[][] diff(List<String> a, List<String> b) {
        boolean[] ca = new boolean[a.size()];
        boolean[] cb = new boolean[b.size()];
        int start = 0;
        while (start < a.size() && start < b.size() && a.get(start).equals(b.get(start))) {
            start++;
        }
        int endA = a.size();
        int endB = b.size();
        while (endA > start && endB > start && a.get(endA - 1).equals(b.get(endB - 1))) {
            endA--;
            endB--;
        }
        int n = endA - start;
        int m = endB - start;
        if ((long) n * m > MAX_CELLS) {
            for (int i = start; i < endA; i++) {
                ca[i] = true;
            }
            for (int j = start; j < endB; j++) {
                cb[j] = true;
            }
            return new boolean[][]{ca, cb};
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(start + i).equals(b.get(start + j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(start + i).equals(b.get(start + j))) {
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ca[start + i++] = true;
            } else {
                cb[start + j++] = true;
            }
        }
        while (i < n) {
            ca[start + i++] = true;
        }
        while (j < m) {
            cb[start + j++] = true;
        }
        return new boolean[][]{ca, cb};
    }
}
