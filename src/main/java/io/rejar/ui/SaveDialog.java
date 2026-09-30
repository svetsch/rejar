package io.rejar.ui;

import io.rejar.core.JarModel;
import io.rejar.core.PendingChange;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;

/** Asks for the new jar file and a description of the change set. */
final class SaveDialog extends Dialog<SaveDialog.Request> {

    record Request(Path target, String description, boolean openSaved) {
    }

    private static final Pattern PATCHED = Pattern.compile("^(.*?)-patched(\\d*)$");

    SaveDialog(JarTab owner) {
        JarModel model = owner.model();
        initOwner(owner.main().stage());
        setTitle("Save as new JAR");
        List<PendingChange> pending = model.pendingList();
        setHeaderText(pending.size() + " pending change(s) will be written to a NEW jar.\n"
                + "The opened jar (" + model.name() + ") is left untouched. The changes (original and new binaries,\n"
                + "edited sources) are recorded in META-INF/rejar/ of the new jar so they can be reverted.");

        TextField target = new TextField(defaultTarget(model.file()).toString());
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("New jar");
            Path current = Path.of(target.getText());
            if (current.getParent() != null && Files.isDirectory(current.getParent())) {
                chooser.setInitialDirectory(current.getParent().toFile());
            }
            chooser.setInitialFileName(current.getFileName().toString());
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java archives", "*.jar", "*.war", "*.ear", "*.zip"));
            File f = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
            if (f != null) {
                target.setText(f.getAbsolutePath());
            }
        });
        TextArea description = new TextArea();
        description.setPromptText("What was changed and why (stored in the jar history)");
        description.setPrefRowCount(4);
        description.setWrapText(true);
        CheckBox open = new CheckBox("Continue working on the new jar (reload this tab with it)");
        open.setSelected(true);
        Label warning = new Label();
        warning.getStyleClass().add("warning-text");
        if (model.isSigned()) {
            warning.setText("⚠ The jar is signed: signature files will be removed (the signature would be invalid).");
        }

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.add(new Label("New jar"), 0, 0);
        grid.add(target, 1, 0);
        grid.add(browse, 2, 0);
        grid.add(new Label("Description"), 0, 1);
        grid.add(description, 1, 1, 2, 1);
        grid.add(open, 1, 2, 2, 1);
        grid.add(warning, 1, 3, 2, 1);
        GridPane.setHgrow(target, Priority.ALWAYS);
        grid.setPrefWidth(760);
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Save");
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            String text = target.getText().trim();
            if (text.isEmpty()) {
                e.consume();
                return;
            }
            Path p = Path.of(text).toAbsolutePath().normalize();
            if (p.equals(model.file())) {
                Fx.warn(getDialogPane().getScene().getWindow(), "Choose another file",
                        "The modified jar is always written to a new file: the opened jar cannot be overwritten.");
                e.consume();
            } else if (Files.exists(p) && !Fx.confirm(getDialogPane().getScene().getWindow(), "File exists",
                    p + " already exists. Replace it?")) {
                e.consume();
            }
        });
        setResultConverter(button -> button == ButtonType.OK
                ? new Request(Path.of(target.getText().trim()).toAbsolutePath().normalize(), description.getText(), open.isSelected())
                : null);
    }

    /** foo.jar -> foo-patched.jar, foo-patched.jar -> foo-patched2.jar ... (first free name). */
    static Path defaultTarget(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".jar";
        Matcher m = PATCHED.matcher(base);
        int n = 1;
        if (m.matches()) {
            base = m.group(1);
            n = m.group(2).isEmpty() ? 2 : Integer.parseInt(m.group(2)) + 1;
        }
        while (true) {
            Path candidate = source.resolveSibling(base + "-patched" + (n == 1 ? "" : Integer.toString(n)) + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            n++;
        }
    }
}
