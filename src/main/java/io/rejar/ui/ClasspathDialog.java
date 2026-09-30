package io.rejar.ui;

import io.rejar.core.ClasspathConfig;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.lang.model.SourceVersion;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

/** Edits the compilation classpath of a jar: jar content, nested jars, Maven dependencies and custom entries. */
final class ClasspathDialog extends Dialog<ClasspathConfig> {

    private final JarTab owner;
    private final ClasspathConfig cfg;
    private final Label mavenStatus = new Label();
    private final ListView<Path> resolved = new ListView<>();
    private final TextArea log = new TextArea();
    private final Button resolveButton = new Button("Resolve Now");
    private AtomicBoolean cancel;

    ClasspathDialog(JarTab owner) {
        this.owner = owner;
        this.cfg = owner.classpathConfig().copy();
        initOwner(owner.main().stage());
        setTitle("Compilation classpath – " + owner.model().name());
        setHeaderText("Classpath used to compile edited sources.\n"
                + "Order: pending changes › jar content › nested jars › Maven dependencies › custom entries › JDK");
        setResizable(true);

        // --- jar content
        Label jar = new Label("✔ Classes of " + owner.model().name() + " (always included, with pending changes first)");
        List<String> nested = owner.model().nestedJars();
        CheckBox nestedBox = new CheckBox("Include jars nested in the jar (" + nested.size() + " found"
                + (nested.isEmpty() ? "" : ", e.g. " + nested.get(0)) + ")");
        nestedBox.setSelected(cfg.includeNestedJars);
        nestedBox.setDisable(nested.isEmpty());
        nestedBox.selectedProperty().addListener((o, a, b) -> cfg.includeNestedJars = b);
        VBox jarBox = new VBox(6, jar, nestedBox);

        // --- maven
        CheckBox useMaven = new CheckBox("Resolve the dependencies of a pom with Maven (compile + provided scopes)");
        useMaven.setSelected(cfg.useMaven);
        ToggleGroup pomSource = new ToggleGroup();
        RadioButton embedded = new RadioButton("Pom embedded in the jar:");
        RadioButton external = new RadioButton("External pom.xml:");
        embedded.setToggleGroup(pomSource);
        external.setToggleGroup(pomSource);
        List<String> poms = owner.model().embeddedPoms();
        ComboBox<String> embeddedCombo = new ComboBox<>();
        embeddedCombo.getItems().setAll(poms);
        embeddedCombo.setMaxWidth(Double.MAX_VALUE);
        embeddedCombo.setValue(cfg.embeddedPom);
        embeddedCombo.setPromptText(poms.isEmpty() ? "(no META-INF/maven/**/pom.xml in this jar)" : "Choose a pom");
        embedded.setDisable(poms.isEmpty());
        TextField externalField = new TextField(cfg.externalPom == null ? "" : cfg.externalPom.toString());
        externalField.setPromptText("path/to/pom.xml");
        Button browsePom = new Button("Browse…");
        browsePom.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose pom.xml");
            chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Maven pom", "*.xml", "*.pom"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            initialDir(chooser);
            File f = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
            if (f != null) {
                externalField.setText(f.getAbsolutePath());
                external.setSelected(true);
            }
        });
        if (cfg.externalPom != null || poms.isEmpty()) {
            external.setSelected(true);
        } else {
            embedded.setSelected(true);
        }
        Runnable syncPom = () -> {
            String previous = cfg.pomLabel();
            if (external.isSelected()) {
                String text = externalField.getText().trim();
                cfg.externalPom = text.isEmpty() ? null : Path.of(text);
            } else {
                cfg.externalPom = null;
            }
            cfg.embeddedPom = embeddedCombo.getValue();
            if (!Objects.equals(previous, cfg.pomLabel())) {
                cfg.mavenClasspath = null;
                updateResolved();
            }
        };
        pomSource.selectedToggleProperty().addListener((o, a, b) -> syncPom.run());
        embeddedCombo.valueProperty().addListener((o, a, b) -> syncPom.run());
        externalField.textProperty().addListener((o, a, b) -> syncPom.run());
        useMaven.selectedProperty().addListener((o, a, b) -> cfg.useMaven = b);

        GridPane pomGrid = new GridPane();
        pomGrid.setHgap(6);
        pomGrid.setVgap(6);
        pomGrid.add(embedded, 0, 0);
        pomGrid.add(embeddedCombo, 1, 0, 2, 1);
        pomGrid.add(external, 0, 1);
        pomGrid.add(externalField, 1, 1);
        pomGrid.add(browsePom, 2, 1);
        GridPane.setHgrow(embeddedCombo, Priority.ALWAYS);
        GridPane.setHgrow(externalField, Priority.ALWAYS);
        pomGrid.disableProperty().bind(useMaven.selectedProperty().not());

        resolveButton.setOnAction(e -> resolve());
        resolveButton.disableProperty().bind(useMaven.selectedProperty().not());
        HBox resolveRow = new HBox(8, resolveButton, mavenStatus);
        resolveRow.setAlignment(Pos.CENTER_LEFT);
        resolved.setPrefHeight(110);
        log.setEditable(false);
        log.setPrefRowCount(8);
        log.getStyleClass().add("console");
        TitledPane logPane = new TitledPane("Maven output", log);
        logPane.setExpanded(false);
        VBox mavenBox = new VBox(6, useMaven, pomGrid, resolveRow, resolved, logPane);

        // --- custom entries
        ListView<Path> custom = new ListView<>();
        custom.getItems().setAll(cfg.customEntries);
        custom.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        custom.setPrefHeight(100);
        custom.setPlaceholder(new Label("No custom entry"));
        Button addJars = new Button("Add JARs…");
        addJars.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Add jars to the classpath");
            chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Jars", "*.jar", "*.zip"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            initialDir(chooser);
            List<File> files = chooser.showOpenMultipleDialog(getDialogPane().getScene().getWindow());
            if (files != null) {
                files.forEach(f -> {
                    if (!custom.getItems().contains(f.toPath())) {
                        custom.getItems().add(f.toPath());
                    }
                });
                Settings.setLastDirectory(files.get(0).getParentFile());
            }
        });
        Button addFolder = new Button("Add Class Folder…");
        addFolder.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Add a class folder to the classpath");
            File dir = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (dir != null && !custom.getItems().contains(dir.toPath())) {
                custom.getItems().add(dir.toPath());
            }
        });
        Button remove = new Button("Remove");
        remove.setOnAction(e -> custom.getItems().removeAll(List.copyOf(custom.getSelectionModel().getSelectedItems())));
        VBox customButtons = new VBox(6, addJars, addFolder, remove);
        addJars.setMaxWidth(Double.MAX_VALUE);
        addFolder.setMaxWidth(Double.MAX_VALUE);
        remove.setMaxWidth(Double.MAX_VALUE);
        HBox customBox = new HBox(6, custom, customButtons);
        HBox.setHgrow(custom, Priority.ALWAYS);

        // --- compiler
        ComboBox<String> release = new ComboBox<>();
        release.getItems().add("Auto (from the class file version)");
        for (int v = 8; v <= SourceVersion.latestSupported().ordinal(); v++) {
            release.getItems().add(Integer.toString(v));
        }
        release.getSelectionModel().select(cfg.release == 0 ? 0 : Math.max(0, release.getItems().indexOf(Integer.toString(cfg.release))));
        TextField javacOptions = new TextField(cfg.javacOptions);
        javacOptions.setPromptText("e.g. -parameters -Xlint:unchecked --add-exports java.base/sun.nio.ch=ALL-UNNAMED");
        GridPane compilerGrid = new GridPane();
        compilerGrid.setHgap(6);
        compilerGrid.setVgap(6);
        compilerGrid.add(new Label("--release"), 0, 0);
        compilerGrid.add(release, 1, 0);
        compilerGrid.add(new Label("Extra javac options"), 0, 1);
        compilerGrid.add(javacOptions, 1, 1);
        GridPane.setHgrow(javacOptions, Priority.ALWAYS);

        VBox root = new VBox(10,
                section("Jar content", jarBox),
                section("Maven dependencies", mavenBox),
                section("Custom classpath entries", customBox),
                section("Compiler", compilerGrid));
        root.setPadding(new Insets(8));
        root.setPrefWidth(820);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        updateResolved();

        setResultConverter(button -> {
            if (cancel != null) {
                cancel.set(true);
            }
            if (button != ButtonType.OK) {
                return null;
            }
            syncPom.run();
            cfg.customEntries = List.copyOf(custom.getItems());
            int index = release.getSelectionModel().getSelectedIndex();
            cfg.release = index <= 0 ? 0 : Integer.parseInt(release.getItems().get(index));
            cfg.javacOptions = javacOptions.getText() == null ? "" : javacOptions.getText().trim();
            cfg.customEntries = new java.util.ArrayList<>(cfg.customEntries);
            return cfg;
        });
    }

    private static TitledPane section(String title, javafx.scene.Node content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setCollapsible(false);
        return pane;
    }

    private static void initialDir(FileChooser chooser) {
        File dir = Settings.lastDirectory();
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
    }

    private void updateResolved() {
        if (cfg.mavenClasspath == null) {
            resolved.getItems().clear();
            mavenStatus.setText(cfg.hasPomSource() ? "Not resolved yet (resolved automatically on first compilation)"
                    : "No pom selected");
        } else {
            resolved.getItems().setAll(cfg.mavenClasspath);
            mavenStatus.setText(cfg.mavenClasspath.size() + " dependencies resolved");
        }
    }

    private void resolve() {
        if (!cfg.hasPomSource()) {
            mavenStatus.setText("Select a pom first");
            return;
        }
        log.clear();
        mavenStatus.setText("Running Maven…");
        resolveButton.disableProperty().unbind();
        resolveButton.setDisable(true);
        cancel = new AtomicBoolean();
        AtomicBoolean myCancel = cancel;
        ClasspathConfig snapshot = cfg.copy();
        Fx.run(() -> owner.resolveMavenBlocking(snapshot, line -> Fx.runLater(() -> {
            log.appendText(line + "\n");
            owner.log(line);
        }), myCancel), deps -> {
            cfg.mavenClasspath = deps;
            resolveButton.setDisable(false);
            updateResolved();
        }, error -> {
            resolveButton.setDisable(false);
            mavenStatus.setText("Failed: " + error.getMessage());
            log.appendText("ERROR: " + error.getMessage() + "\n");
        });
    }
}
