package io.rejar.ui;

import io.rejar.core.ClassNames;
import io.rejar.core.ClassUnit;
import io.rejar.core.History;
import io.rejar.core.HistoryStore;
import io.rejar.core.JarModel;
import io.rejar.core.PendingSource;
import io.rejar.core.SourceCompiler;
import io.rejar.core.TextSupport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Editor tab of a class unit: decompiled source (editable) and bytecode views. */
final class ClassEditorTab extends Tab {

    private static final KeyCombination COMPILE = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination EDIT = new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN);

    private final JarTab owner;
    private final String unitId;
    private final String simpleName;
    private final CodeEditor sourceEditor = new CodeEditor(Highlighter.Language.JAVA);
    private final CodeEditor bytecodeEditor = new CodeEditor(Highlighter.Language.BYTECODE);
    private final ToggleButton sourceView = new ToggleButton("Source");
    private final ToggleButton bytecodeView = new ToggleButton("Bytecode");
    private final ToggleButton editButton = new ToggleButton("✎ Edit");
    private final Button compileButton = new Button("▶ Compile & Apply");
    private final Button resetButton = new Button("Discard Edits");
    private final Button revertButton = new Button("Revert Class");
    private final Button compareButton = new Button("Compare");
    private final Button storedSourceButton = new Button("Load Stored Source");
    private final Label info = new Label();
    private final ListView<SourceCompiler.Problem> problems = new ListView<>();
    private final VBox problemsBox;
    private final BorderPane content = new BorderPane();
    private final BooleanProperty dirty = new SimpleBooleanProperty();
    private final List<Runnable> onLoaded = new ArrayList<>();

    private String loadedSource = "";
    private String originalSource;
    private String storedSource;
    private String storedSourceChangeSet;
    private boolean loaded;
    private boolean compiling;
    private boolean bytecodeLoaded;

    ClassEditorTab(JarTab owner, ClassUnit unit) {
        this.owner = owner;
        this.unitId = unit.id();
        this.simpleName = unit.simpleName();
        setText(simpleName);
        setTooltip(new Tooltip(unit.fqcn() + (unit.prefix().isEmpty() ? "" : "\n(" + unit.prefix() + ")")));

        ToggleGroup views = new ToggleGroup();
        sourceView.setToggleGroup(views);
        bytecodeView.setToggleGroup(views);
        sourceView.setSelected(true);
        views.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null) {
                o.setSelected(true);
            } else {
                showView();
            }
        });

        editButton.setTooltip(new Tooltip("Edit the source (Ctrl+E)"));
        editButton.setOnAction(e -> setEditing(editButton.isSelected()));
        compileButton.setTooltip(new Tooltip("Compile the source and replace the classes in the jar (Ctrl+S).\n"
                + "Nothing is written to disk until 'Save as New JAR'."));
        compileButton.getStyleClass().add("accent");
        compileButton.setOnAction(e -> compile());
        resetButton.setOnAction(e -> {
            if (Fx.confirm(Fx.window(content), "Discard edits", "Revert the editor to the last applied source?")) {
                sourceEditor.setText(loadedSource);
                problems.getItems().clear();
            }
        });
        revertButton.setTooltip(new Tooltip("Drop the pending (unsaved) changes of this class"));
        revertButton.setOnAction(e -> {
            if (Fx.confirm(Fx.window(content), "Revert " + simpleName,
                    "Drop the pending changes of this class and go back to its bytes in the opened jar?")) {
                owner.model().discardUnit(unitId);
            }
        });
        compareButton.setTooltip(new Tooltip("Compare the originally decompiled source with the current source"));
        compareButton.setOnAction(e -> owner.openCompare(simpleName, originalSource, sourceEditor.getText(),
                "Decompiled (original)", "Current source"));
        storedSourceButton.setOnAction(e -> {
            sourceView.setSelected(true);
            setEditing(true);
            sourceEditor.setText(storedSource);
            dirty.set(!storedSource.equals(loadedSource));
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        info.getStyleClass().add("editor-info");
        ToolBar toolbar = new ToolBar(sourceView, bytecodeView, new Separator(), editButton, compileButton,
                resetButton, storedSourceButton, new Separator(), revertButton, compareButton, spacer, info);
        toolbar.getStyleClass().add("editor-toolbar");

        problems.setPrefHeight(130);
        problems.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SourceCompiler.Problem p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().removeAll("problem-error", "problem-warning");
                if (empty || p == null) {
                    setText(null);
                } else {
                    setText(p.toString());
                    getStyleClass().add(p.isError() ? "problem-error" : "problem-warning");
                }
            }
        });
        problems.setOnMouseClicked(e -> gotoProblem(problems.getSelectionModel().getSelectedItem()));
        problems.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                gotoProblem(problems.getSelectionModel().getSelectedItem());
            }
        });
        Label problemsTitle = new Label("Compilation problems");
        problemsTitle.getStyleClass().add("section-title");
        Button closeProblems = new Button("✕");
        closeProblems.setOnAction(e -> problems.getItems().clear());
        Region s2 = new Region();
        HBox.setHgrow(s2, Priority.ALWAYS);
        HBox problemsHeader = new HBox(6, problemsTitle, s2, closeProblems);
        problemsHeader.setAlignment(Pos.CENTER_LEFT);
        problemsHeader.setPadding(new Insets(2, 6, 2, 6));
        problemsBox = new VBox(problemsHeader, problems);
        problemsBox.getStyleClass().add("problems");
        problemsBox.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(problems.getItems()));
        problemsBox.managedProperty().bind(problemsBox.visibleProperty());

        content.setTop(toolbar);
        content.setCenter(sourceEditor);
        content.setBottom(problemsBox);
        setContent(content);

        sourceEditor.setOnNavigate(word -> owner.navigate(word, sourceEditor.getText()));
        bytecodeEditor.setOnNavigate(word -> owner.navigate(word.replace('/', '.'), ""));
        sourceEditor.textProperty().addListener((obs, o, n) -> {
            if (loaded) {
                dirty.set(!n.equals(loadedSource));
            }
        });
        dirty.addListener((obs, o, n) -> updateTitle());
        content.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (COMPILE.match(e)) {
                if (sourceEditor.isEditable()) {
                    compile();
                }
                e.consume();
            } else if (EDIT.match(e)) {
                editButton.setSelected(!editButton.isSelected());
                setEditing(editButton.isSelected());
                e.consume();
            }
        });
        setEditing(false);
        load();
    }

    String unitId() {
        return unitId;
    }

    boolean isDirty() {
        return dirty.get();
    }

    String sourceText() {
        return sourceEditor.getText();
    }

    String originalSource() {
        return originalSource;
    }

    CodeEditor currentEditor() {
        return bytecodeView.isSelected() ? bytecodeEditor : sourceEditor;
    }

    void applyFontSize() {
        sourceEditor.applyFontSize();
        bytecodeEditor.applyFontSize();
    }

    /** Runs the action once the source is loaded (immediately if already loaded). */
    void whenLoaded(Runnable action) {
        if (loaded) {
            action.run();
        } else {
            onLoaded.add(action);
        }
    }

    void showBytecode() {
        bytecodeView.setSelected(true);
    }

    void startEditing() {
        sourceView.setSelected(true);
        whenLoaded(() -> {
            editButton.setSelected(true);
            setEditing(true);
        });
    }

    void goTo(int line, int column, int length) {
        sourceView.setSelected(true);
        whenLoaded(() -> sourceEditor.goTo(line, column, length));
    }

    void reveal(Pattern pattern) {
        whenLoaded(() -> sourceEditor.reveal(pattern));
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        loaded = false;
        bytecodeLoaded = false;
        JarModel model = owner.model();
        ClassUnit unit = model.unit(unitId);
        if (unit == null) {
            loadedSource = "// " + ClassNames.toFqcn(unitId) + " does not exist in the jar anymore";
            sourceEditor.setText(loadedSource);
            loaded = true;
            updateState();
            return;
        }
        sourceEditor.setText("// Decompiling " + unit.fqcn() + "…");
        Fx.run(() -> {
            PendingSource pending = model.pendingSource(unitId);
            String decompiled = pending != null && pending.originalSource() != null
                    ? pending.originalSource()
                    : owner.decompiler().decompile(unit);
            String shown = pending != null ? pending.source() : owner.decompiler().decompile(unit);
            findStoredSource(model, unit);
            return new String[]{shown, decompiled};
        }, result -> {
            loadedSource = result[0];
            originalSource = result[1];
            sourceEditor.setText(loadedSource);
            loaded = true;
            dirty.set(false);
            problems.getItems().clear();
            updateState();
            if (bytecodeView.isSelected()) {
                loadBytecode();
            }
            List<Runnable> callbacks = new ArrayList<>(onLoaded);
            onLoaded.clear();
            callbacks.forEach(Runnable::run);
        }, error -> {
            loadedSource = "// Cannot decompile: " + error;
            sourceEditor.setText(loadedSource);
            loaded = true;
            updateState();
        });
    }

    /** Looks in the jar history for the edited source that produced the current bytes of this unit. */
    private void findStoredSource(JarModel model, ClassUnit unit) {
        storedSource = null;
        storedSourceChangeSet = null;
        try {
            String currentSha = HistoryStore.sha256(model.read(unit.outerEntry()));
            List<History.ChangeSet> sets = model.history().changeSets;
            for (int i = sets.size() - 1; i >= 0; i--) {
                History.ChangeSet cs = sets.get(i);
                for (History.SourceChange sc : cs.sources) {
                    if (unitId.equals(sc.unit)) {
                        boolean matches = cs.entries.stream().anyMatch(e -> e.path.equals(unit.outerEntry())
                                && currentSha != null && currentSha.equals(e.newSha256));
                        byte[] src = model.read(sc.modifiedSource);
                        if (matches && src != null) {
                            storedSource = new String(src, StandardCharsets.UTF_8);
                            storedSourceChangeSet = cs.id;
                        }
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
            // no stored source
        }
    }

    private void loadBytecode() {
        if (bytecodeLoaded) {
            return;
        }
        bytecodeLoaded = true;
        JarModel model = owner.model();
        ClassUnit unit = model.unit(unitId);
        if (unit == null) {
            bytecodeEditor.setText("");
            return;
        }
        bytecodeEditor.setText("// Disassembling…");
        Fx.run(() -> {
            StringBuilder sb = new StringBuilder();
            for (String entry : unit.entries()) {
                byte[] bytes = model.read(entry);
                if (bytes == null) {
                    continue;
                }
                int major = ClassNames.majorVersion(bytes);
                sb.append("// ===== ").append(entry).append("  (class version ").append(major)
                        .append(", Java ").append(major - 44).append(", ").append(bytes.length).append(" bytes)\n");
                sb.append(TextSupport.bytecode(bytes)).append('\n');
            }
            return sb.toString();
        }, bytecodeEditor::setText, error -> bytecodeEditor.setText("// " + error));
    }

    private void showView() {
        if (bytecodeView.isSelected()) {
            loadBytecode();
            content.setCenter(bytecodeEditor);
        } else {
            content.setCenter(sourceEditor);
        }
        updateState();
    }

    // ------------------------------------------------------------------ editing

    private void setEditing(boolean editing) {
        if (editing && !SourceCompiler.isAvailable()) {
            Fx.warn(Fx.window(content), "Compiler not available",
                    "ReJar runs on a JRE: start it with a JDK to compile modified sources.");
            editButton.setSelected(false);
            return;
        }
        if (!editing && dirty.get()) {
            if (!Fx.confirm(Fx.window(content), "Stop editing", "Discard the edits that were not compiled?")) {
                editButton.setSelected(true);
                return;
            }
            sourceEditor.setText(loadedSource);
        }
        if (editing) {
            sourceView.setSelected(true);
        }
        editButton.setSelected(editing);
        sourceEditor.setEditable(editing);
        if (editing) {
            sourceEditor.area().requestFocus();
        }
        updateState();
    }

    private void compile() {
        if (compiling || !loaded) {
            return;
        }
        owner.compileAndApply(this);
    }

    void compilationStarted() {
        compiling = true;
        problems.getItems().clear();
        info.setText("Compiling…");
        updateState();
    }

    /** Called by the jar tab once compilation finished (success or failure). */
    void compilationFinished(SourceCompiler.Result result, String compiledSource, int release) {
        compiling = false;
        List<SourceCompiler.Problem> list = new ArrayList<>(result.problems());
        list.sort((a, b) -> Boolean.compare(!a.isError(), !b.isError()));
        problems.getItems().setAll(list);
        if (result.success()) {
            loadedSource = compiledSource;
            dirty.set(!sourceEditor.getText().equals(loadedSource));
        } else if (!list.isEmpty()) {
            gotoProblem(list.get(0));
        }
        updateState();
        info.setText(result.success()
                ? "Compiled for Java " + release + " – " + result.classes().size()
                        + " class file(s) applied (not saved yet)"
                : "Compilation failed: " + list.stream().filter(SourceCompiler.Problem::isError).count() + " error(s)");
    }

    void compilationError() {
        compiling = false;
        updateState();
    }

    private void gotoProblem(SourceCompiler.Problem p) {
        if (p == null || p.line() <= 0) {
            return;
        }
        sourceView.setSelected(true);
        int length = p.endOffset() > p.startOffset() && p.startOffset() >= 0 ? (int) (p.endOffset() - p.startOffset()) : 1;
        sourceEditor.goTo((int) p.line(), (int) Math.max(0, p.column() - 1), Math.min(length, 200));
    }

    /** Reacts to model changes affecting this unit. */
    void modelChanged(Set<String> paths) {
        String outer = unitId + ClassNames.CLASS_SUFFIX;
        boolean affected = paths.stream().anyMatch(p -> p.equals(outer) || p.startsWith(unitId + "$"));
        if (!affected) {
            return;
        }
        PendingSource pending = owner.model().pendingSource(unitId);
        if (pending != null && pending.source().equals(sourceEditor.getText())) {
            loadedSource = pending.source();
            dirty.set(false);
            updateState();
            return;
        }
        if (!dirty.get()) {
            boolean editing = sourceEditor.isEditable();
            load();
            if (editing) {
                whenLoaded(() -> sourceEditor.setEditable(true));
            }
        } else {
            updateState();
        }
    }

    private void updateTitle() {
        setText((dirty.get() ? "*" : "") + simpleName);
    }

    private void updateState() {
        boolean editing = sourceEditor.isEditable();
        boolean modified = owner.model().isUnitModified(unitId);
        compileButton.setDisable(!editing || compiling || bytecodeView.isSelected());
        resetButton.setDisable(!editing || !dirty.get());
        revertButton.setDisable(!modified);
        compareButton.setDisable(originalSource == null);
        storedSourceButton.setVisible(storedSource != null);
        storedSourceButton.setManaged(storedSource != null);
        if (storedSource != null) {
            storedSourceButton.setTooltip(new Tooltip("Load the source edited in change set " + storedSourceChangeSet
                    + " (stored in this jar) instead of the decompiled one"));
        }
        getStyleClass().removeAll("modified-tab");
        if (modified) {
            getStyleClass().add("modified-tab");
        }
        if (!compiling) {
            List<String> parts = new ArrayList<>();
            ClassUnit unit = owner.model().unit(unitId);
            if (unit != null) {
                try {
                    int major = ClassNames.majorVersion(owner.model().read(unit.outerEntry()));
                    parts.add("Java " + (major - 44) + " (class v" + major + ")");
                } catch (Exception ignored) {
                    // unreadable
                }
                parts.add(unit.entries().size() + " class file(s)");
            }
            if (modified) {
                parts.add("modified (unsaved)");
            }
            if (loaded && loadedSource.contains("$VF: ")) {
                parts.add("⚠ decompiler reported issues");
            }
            if (editing) {
                parts.add("editing");
            }
            info.setText(String.join("  ·  ", parts));
        }
    }
}
