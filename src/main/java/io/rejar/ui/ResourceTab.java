package io.rejar.ui;

import io.rejar.core.JarModel;
import io.rejar.core.TextSupport;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/** Viewer/editor of a resource entry: text (editable), image or hex dump. */
final class ResourceTab extends Tab {

    private static final int HEX_LIMIT = 1024 * 1024;
    private static final KeyCombination APPLY = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);

    private final JarTab owner;
    private final String path;
    private final String name;
    private final BorderPane content = new BorderPane();
    private final CodeEditor editor;
    private final ToggleButton editButton = new ToggleButton("✎ Edit");
    private final Button applyButton = new Button("✔ Apply");
    private final Button resetButton = new Button("Discard Edits");
    private final Button revertButton = new Button("Revert");
    private final Label info = new Label();
    private final BooleanProperty dirty = new SimpleBooleanProperty();
    private final List<Runnable> onLoaded = new ArrayList<>();
    private String loadedText = "";
    private Charset charset = StandardCharsets.UTF_8;
    private boolean text;
    private boolean loaded;
    private boolean selfApplied;

    ResourceTab(JarTab owner, String path) {
        this.owner = owner;
        this.path = path;
        this.name = path.substring(path.lastIndexOf('/') + 1);
        setText(name);
        setTooltip(new Tooltip(path));
        editor = new CodeEditor(Highlighter.forPath(path));

        editButton.setOnAction(e -> setEditing(editButton.isSelected()));
        applyButton.getStyleClass().add("accent");
        applyButton.setTooltip(new Tooltip("Replace the resource in the jar (Ctrl+S). Saved with 'Save as New JAR'."));
        applyButton.setOnAction(e -> apply());
        resetButton.setOnAction(e -> editor.setText(loadedText));
        revertButton.setTooltip(new Tooltip("Drop the pending change of this entry"));
        revertButton.setOnAction(e -> owner.model().discard(List.of(path)));
        Button extract = new Button("Extract…");
        extract.setOnAction(e -> owner.extract(path));
        Button replace = new Button("Replace with File…");
        replace.setOnAction(e -> owner.replaceWithFile(path));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        info.getStyleClass().add("editor-info");
        ToolBar toolbar = new ToolBar(editButton, applyButton, resetButton, new Separator(), extract, replace,
                revertButton, spacer, info);
        toolbar.getStyleClass().add("editor-toolbar");
        content.setTop(toolbar);
        setContent(content);

        editor.textProperty().addListener((obs, o, n) -> {
            if (loaded && text) {
                dirty.set(!n.equals(loadedText));
            }
        });
        dirty.addListener((obs, o, n) -> setText((n ? "*" : "") + name));
        content.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (APPLY.match(e) && editor.isEditable()) {
                apply();
                e.consume();
            }
        });
        load();
    }

    String path() {
        return path;
    }

    boolean isDirty() {
        return dirty.get();
    }

    CodeEditor editor() {
        return editor;
    }

    void applyFontSize() {
        editor.applyFontSize();
    }

    void goTo(int line, int column, int length) {
        if (loaded) {
            editor.goTo(line, column, length);
        } else {
            onLoaded.add(() -> editor.goTo(line, column, length));
        }
    }

    private void load() {
        loaded = false;
        JarModel model = owner.model();
        Fx.run(() -> model.read(path), data -> {
            if (data == null) {
                content.setCenter(new Label("Entry does not exist anymore"));
                return;
            }
            text = TextSupport.isProbablyText(path, data);
            if (TextSupport.isImage(path)) {
                Image image = new Image(new ByteArrayInputStream(data));
                ImageView view = new ImageView(image);
                StackPane holder = new StackPane(view);
                holder.setAlignment(Pos.CENTER);
                holder.getStyleClass().add("image-holder");
                ScrollPane scroll = new ScrollPane(holder);
                scroll.setFitToWidth(true);
                scroll.setFitToHeight(true);
                content.setCenter(scroll);
                info.setText((int) image.getWidth() + " × " + (int) image.getHeight() + " px · "
                        + formatSize(data.length));
            } else if (text) {
                charset = TextSupport.isUtf8(data) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
                loadedText = new String(data, charset);
                editor.setText(loadedText);
                content.setCenter(editor);
                info.setText(formatSize(data.length) + " · " + charset.name());
            } else {
                editor.setLanguage(Highlighter.Language.PLAIN);
                editor.setText(TextSupport.hexDump(data, HEX_LIMIT));
                content.setCenter(editor);
                info.setText("binary · " + formatSize(data.length));
            }
            loaded = true;
            dirty.set(false);
            updateState();
            List<Runnable> callbacks = new ArrayList<>(onLoaded);
            onLoaded.clear();
            callbacks.forEach(Runnable::run);
        }, error -> content.setCenter(new Label("Cannot read entry: " + error.getMessage())));
    }

    private void setEditing(boolean editing) {
        if (!editing && dirty.get()) {
            if (!Fx.confirm(Fx.window(content), "Stop editing", "Discard the edits that were not applied?")) {
                editButton.setSelected(true);
                return;
            }
            editor.setText(loadedText);
        }
        editButton.setSelected(editing);
        editor.setEditable(editing);
        updateState();
    }

    private void apply() {
        String value = editor.getText();
        byte[] bytes = value.getBytes(charset);
        loadedText = value;
        dirty.set(false);
        selfApplied = true;
        owner.model().putResource(path, bytes, "edited");
        selfApplied = false;
        owner.main().setStatus("Applied changes to " + path + " (not saved yet)");
    }

    void modelChanged(Set<String> paths) {
        if (!paths.contains(path)) {
            return;
        }
        if (!dirty.get() && !selfApplied) {
            boolean editing = editor.isEditable();
            load();
            onLoaded.add(() -> editor.setEditable(editing));
        }
        updateState();
    }

    private void updateState() {
        editButton.setDisable(!text);
        applyButton.setDisable(!editor.isEditable());
        resetButton.setDisable(!editor.isEditable());
        revertButton.setDisable(!owner.model().isModified(path));
    }

    private static String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        }
        return String.format("%.1f MB", size / (1024.0 * 1024));
    }
}
