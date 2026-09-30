package io.rejar.ui;

import io.rejar.core.MavenResolver;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Global settings: Maven executable/arguments, editor font size, theme. */
final class SettingsDialog extends Dialog<Boolean> {

    SettingsDialog(Window owner) {
        initOwner(owner);
        setTitle("Settings");
        setHeaderText("ReJar settings");

        TextField mvn = new TextField(Settings.mavenExecutable());
        Optional<Path> detected = MavenResolver.findMaven(null);
        mvn.setPromptText(detected.map(p -> "auto-detected: " + p).orElse("mvn not found in PATH / MAVEN_HOME"));
        Button browse = new Button("Browse\u2026");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Maven executable (mvn / mvn.cmd)");
            File f = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
            if (f != null) {
                mvn.setText(f.getAbsolutePath());
            }
        });
        TextField args = new TextField(Settings.mavenArgs());
        args.setPromptText("e.g. -o  or  -s C:\\path\\settings.xml");
        Spinner<Integer> font = new Spinner<>(8, 32, Settings.fontSize());
        font.setEditable(true);
        CheckBox dark = new CheckBox("Dark theme");
        dark.setSelected(Settings.darkTheme());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.add(new Label("Maven executable"), 0, 0);
        grid.add(mvn, 1, 0);
        grid.add(browse, 2, 0);
        grid.add(new Label("Extra Maven arguments"), 0, 1);
        grid.add(args, 1, 1, 2, 1);
        grid.add(new Label("Editor font size"), 0, 2);
        grid.add(font, 1, 2);
        grid.add(dark, 1, 3);
        GridPane.setHgrow(mvn, Priority.ALWAYS);
        grid.setPrefWidth(640);
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return false;
            }
            Settings.setMavenExecutable(mvn.getText());
            Settings.setMavenArgs(args.getText());
            Settings.setFontSize(font.getValue());
            Settings.setDarkTheme(dark.isSelected());
            return true;
        });
    }
}
