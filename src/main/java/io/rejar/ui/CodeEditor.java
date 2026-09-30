package io.rejar.ui;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;

/** Syntax highlighted code view/editor with line numbers, find bar and go-to-line. */
public class CodeEditor extends BorderPane {

    private static final int ASYNC_HIGHLIGHT_THRESHOLD = 150_000;
    private static final KeyCombination FIND = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination GOTO = new KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination FIND_PREV = new KeyCodeCombination(KeyCode.F3, KeyCombination.SHIFT_DOWN);

    private final CodeArea area = new CodeArea();
    private Highlighter.Language language;
    private final HBox findBar;
    private final TextField findField = new TextField();
    private final CheckBox matchCase = new CheckBox("Match case");
    private final Label findStatus = new Label();
    private Consumer<String> onNavigate;
    private long highlightGeneration;

    public CodeEditor(Highlighter.Language language) {
        this.language = language;
        getStyleClass().add("code-editor");
        area.getStyleClass().add("code");
        area.setEditable(false);
        applyFontSize();
        setCenter(new VirtualizedScrollPane<>(area));

        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(120))
                .subscribe(changes -> highlight());

        findField.setPromptText("Find in file");
        findField.setPrefColumnCount(28);
        findField.setOnAction(e -> find(true));
        findField.textProperty().addListener((obs, o, n) -> findStatus.setText(""));
        Button next = new Button("▼");
        next.setTooltip(new javafx.scene.control.Tooltip("Next (Enter / F3)"));
        next.setOnAction(e -> find(true));
        Button prev = new Button("▲");
        prev.setTooltip(new javafx.scene.control.Tooltip("Previous (Shift+F3)"));
        prev.setOnAction(e -> find(false));
        Button close = new Button("✕");
        close.setOnAction(e -> hideFind());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        findBar = new HBox(6, new Label("Find:"), findField, prev, next, matchCase, findStatus, spacer, close);
        findBar.setAlignment(Pos.CENTER_LEFT);
        findBar.setPadding(new Insets(4, 6, 4, 6));
        findBar.getStyleClass().add("find-bar");
        findField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hideFind();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && e.isShiftDown()) {
                find(false);
                e.consume();
            }
        });

        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (FIND.match(e)) {
                showFind();
                e.consume();
            } else if (GOTO.match(e)) {
                promptGoToLine();
                e.consume();
            } else if (FIND_PREV.match(e)) {
                find(false);
                e.consume();
            } else if (e.getCode() == KeyCode.F3) {
                find(true);
                e.consume();
            }
        });
        area.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (area.isEditable() && e.getCode() == KeyCode.ENTER && !e.isShortcutDown()) {
                autoIndent();
            }
        });
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (area.isEditable() && e.getCode() == KeyCode.TAB && !e.isShortcutDown() && !e.isShiftDown()
                    && area.getSelection().getLength() == 0) {
                area.insertText(area.getCaretPosition(), "    ");
                e.consume();
            }
        });
        area.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isShortcutDown() && onNavigate != null) {
                int pos = area.hit(e.getX(), e.getY()).getInsertionIndex();
                String word = wordAt(pos);
                if (!word.isEmpty()) {
                    onNavigate.accept(word);
                }
            }
        });
    }

    public CodeArea area() {
        return area;
    }

    public void setLanguage(Highlighter.Language language) {
        this.language = language;
        highlight();
    }

    /** Ctrl+click on an identifier (receives the identifier, possibly dotted). */
    public void setOnNavigate(Consumer<String> handler) {
        this.onNavigate = handler;
    }

    public void setText(String text) {
        area.replaceText(text == null ? "" : text);
        area.getUndoManager().forgetHistory();
        area.getUndoManager().mark();
        area.moveTo(0);
        area.showParagraphAtTop(0);
        highlight();
    }

    public String getText() {
        return area.getText();
    }

    public ObservableValue<String> textProperty() {
        return area.textProperty();
    }

    public void setEditable(boolean editable) {
        area.setEditable(editable);
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("editing"), editable);
    }

    public boolean isEditable() {
        return area.isEditable();
    }

    public void applyFontSize() {
        String font = "-fx-font-family: \"" + Fx.monospaceFamily() + "\"; -fx-font-size: " + Settings.fontSize() + "px;";
        area.setStyle(font);
        // RichTextFX line numbers use a hard-coded italic font: override it inline
        IntFunction<Node> numbers = LineNumberFactory.get(area);
        area.setParagraphGraphicFactory(line -> {
            Node node = numbers.apply(line);
            node.setStyle(font + " -fx-font-style: normal;");
            return node;
        });
    }

    /** Selects a range given a 1-based line, 0-based column and length, and scrolls it to the center. */
    public void goTo(int line, int column, int length) {
        Platform.runLater(() -> {
            int paragraphs = area.getParagraphs().size();
            if (paragraphs == 0) {
                return;
            }
            int par = Math.max(0, Math.min(line - 1, paragraphs - 1));
            int parLength = area.getParagraphLength(par);
            int col = Math.max(0, Math.min(column, parLength));
            int start = area.getAbsolutePosition(par, col);
            int end = Math.min(start + Math.max(0, length), area.getLength());
            area.selectRange(start, end);
            area.showParagraphAtCenter(par);
            area.requestFocus();
        });
    }

    /** Selects the first occurrence of a text (used to reveal a declaration). */
    public boolean reveal(Pattern pattern) {
        Matcher m = pattern.matcher(area.getText());
        if (m.find()) {
            int start = m.groupCount() > 0 && m.start(1) >= 0 ? m.start(1) : m.start();
            int end = m.groupCount() > 0 && m.start(1) >= 0 ? m.end(1) : m.end();
            Platform.runLater(() -> {
                area.selectRange(start, end);
                area.showParagraphAtCenter(area.getCurrentParagraph());
            });
            return true;
        }
        return false;
    }

    public void showFind() {
        if (getTop() != findBar) {
            setTop(findBar);
        }
        String selected = area.getSelectedText();
        if (!selected.isEmpty() && !selected.contains("\n")) {
            findField.setText(selected);
        }
        findField.requestFocus();
        findField.selectAll();
    }

    private void hideFind() {
        setTop(null);
        area.requestFocus();
    }

    private void find(boolean forward) {
        String query = findField.getText();
        if (query == null || query.isEmpty()) {
            showFind();
            return;
        }
        Pattern p = Pattern.compile(Pattern.quote(query),
                matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        String text = area.getText();
        Matcher m = p.matcher(text);
        int total = 0;
        int firstStart = -1;
        int lastStart = -1;
        int chosen = -1;
        int chosenIndex = 0;
        int selStart = area.getSelection().getStart();
        int selEnd = area.getSelection().getEnd();
        while (m.find()) {
            total++;
            if (firstStart < 0) {
                firstStart = m.start();
            }
            lastStart = m.start();
            if (forward && chosen < 0 && m.start() >= selEnd && !(m.start() == selStart && m.end() == selEnd)) {
                chosen = m.start();
                chosenIndex = total;
            }
            if (!forward && m.start() < selStart) {
                chosen = m.start();
                chosenIndex = total;
            }
        }
        if (total == 0) {
            findStatus.setText("No match");
            return;
        }
        if (chosen < 0) {
            chosen = forward ? firstStart : lastStart;
            chosenIndex = forward ? 1 : total;
        }
        area.selectRange(chosen, chosen + query.length());
        area.showParagraphAtCenter(area.getCurrentParagraph());
        findStatus.setText(chosenIndex + " of " + total);
    }

    private void promptGoToLine() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(Fx.window(this));
        dialog.setTitle("Go to line");
        dialog.setHeaderText("Line number (1-" + area.getParagraphs().size() + ")");
        dialog.showAndWait().ifPresent(value -> {
            try {
                goTo(Integer.parseInt(value.trim()), 0, 0);
            } catch (NumberFormatException ignored) {
                // ignore invalid input
            }
        });
    }

    private void autoIndent() {
        int par = area.getCurrentParagraph();
        if (par == 0) {
            return;
        }
        String previous = area.getParagraph(par - 1).getText();
        int i = 0;
        while (i < previous.length() && (previous.charAt(i) == ' ' || previous.charAt(i) == '\t')) {
            i++;
        }
        String indent = previous.substring(0, i);
        if (previous.stripTrailing().endsWith("{")) {
            indent += "    ";
        }
        if (!indent.isEmpty()) {
            String ws = indent;
            Platform.runLater(() -> area.insertText(area.getCaretPosition(), ws));
        }
    }

    private String wordAt(int pos) {
        String text = area.getText();
        int start = Math.min(pos, text.length());
        int end = start;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }

    private static boolean isWordChar(char c) {
        return Character.isJavaIdentifierPart(c) || c == '.';
    }

    private void highlight() {
        String text = area.getText();
        Highlighter.Language lang = language;
        long generation = ++highlightGeneration;
        if (text.length() < ASYNC_HIGHLIGHT_THRESHOLD) {
            applySpans(text, Highlighter.compute(text, lang));
        } else {
            Fx.run(() -> Highlighter.compute(text, lang), spans -> {
                if (generation == highlightGeneration) {
                    applySpans(text, spans);
                }
            }, null);
        }
    }

    private void applySpans(String text, StyleSpans<Collection<String>> spans) {
        if (area.getLength() == text.length() && area.getText().equals(text)) {
            area.setStyleSpans(0, spans);
        }
    }
}
