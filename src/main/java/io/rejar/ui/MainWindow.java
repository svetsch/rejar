package io.rejar.ui;

import io.rejar.core.JarModel;
import io.rejar.core.SourceCompiler;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/** Main application window: one tab per opened jar. */
public final class MainWindow {

    private final Stage stage;
    private final HostServices hostServices;
    private final TabPane jarTabs = new TabPane();
    private final Label status = new Label("Ready");
    private final ProgressBar progress = new ProgressBar();
    private final Menu recentMenu = new Menu("Open _Recent");
    private final Scene scene;

    public MainWindow(Stage stage, HostServices hostServices) {
        this.stage = stage;
        this.hostServices = hostServices;

        jarTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        jarTabs.getStyleClass().add("jar-tabs");

        VBox placeholder = new VBox(10);
        placeholder.setAlignment(Pos.CENTER);
        Label title = new Label("ReJar");
        title.getStyleClass().add("placeholder-title");
        Label hint = new Label("Open a jar with File › Open JAR… (Ctrl+O) or drop jar files here");
        hint.getStyleClass().add("placeholder-hint");
        javafx.scene.image.ImageView logo = Icons.view(96);
        logo.setOpacity(0.85);
        placeholder.getChildren().addAll(logo, title, hint);
        placeholder.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(jarTabs.getTabs()));
        placeholder.setMouseTransparent(true);

        StackPane center = new StackPane(jarTabs, placeholder);

        progress.setPrefWidth(160);
        progress.setVisible(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label jdk = new Label(SourceCompiler.isAvailable()
                ? "Java " + Runtime.version().feature() + " (compiler available)"
                : "JRE: compilation unavailable (run ReJar on a JDK)");
        jdk.getStyleClass().add("status-info");
        HBox statusBar = new HBox(10, status, spacer, progress, jdk);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(3, 8, 3, 8));
        statusBar.getStyleClass().add("status-bar");

        BorderPane root = new BorderPane(center);
        root.setTop(createMenuBar());
        root.setBottom(statusBar);

        scene = new Scene(root, 1400, 900);
        applyTheme();
        stage.setScene(scene);
        stage.setTitle("ReJar - jar decompiler & patcher");
        stage.getIcons().setAll(Icons.all());

        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            boolean done = false;
            if (e.getDragboard().hasFiles()) {
                for (File f : e.getDragboard().getFiles()) {
                    if (f.isFile()) {
                        openJar(f.toPath());
                        done = true;
                    }
                }
            }
            e.setDropCompleted(done);
            e.consume();
        });
        stage.setOnCloseRequest(e -> {
            if (!confirmDiscard(jarTabs(), "Quit ReJar")) {
                e.consume();
            }
        });
    }

    public void show() {
        stage.show();
    }

    public Stage stage() {
        return stage;
    }

    public HostServices hostServices() {
        return hostServices;
    }

    private MenuBar createMenuBar() {
        MenuItem open = item("_Open JAR…", "Shortcut+O", this::chooseAndOpen);
        rebuildRecentMenu();
        MenuItem save = item("_Save as New JAR…", "Shortcut+Shift+S", () -> withJar(JarTab::saveAsNewJar));
        MenuItem sourceJar = item("Create Source _JAR…", null, () -> withJar(JarTab::createSourceJar));
        MenuItem close = item("_Close JAR", "Shortcut+Shift+W", () -> withJar(t -> closeJarTab(t)));
        MenuItem exit = item("E_xit", null, () -> {
            if (confirmDiscard(jarTabs(), "Quit ReJar")) {
                Platform.exit();
            }
        });
        Menu file = new Menu("_File", null, open, recentMenu, new SeparatorMenuItem(), save, sourceJar, close,
                new SeparatorMenuItem(), exit);

        MenuItem search = item("_Search in JAR…", "Shortcut+Shift+F", () -> withJar(JarTab::showSearch));
        MenuItem gotoClass = item("_Go to Class…", "Shortcut+N", () -> withJar(JarTab::gotoClass));
        MenuItem findInFile = item("_Find in File", "Shortcut+F", () -> withJar(JarTab::findInCurrentEditor));
        Menu navigate = new Menu("_Navigate", null, gotoClass, search, findInFile);

        MenuItem classpath = item("Compilation _Classpath…", null, () -> withJar(JarTab::editClasspath));
        MenuItem changes = item("Pending Changes && _History", null, () -> withJar(JarTab::showChanges));
        MenuItem settings = item("_Settings…", null, this::openSettings);
        Menu tools = new Menu("_Tools", null, classpath, changes, new SeparatorMenuItem(), settings);

        MenuItem about = item("_About", null, () -> showAbout(
                "Jar decompiler and patcher.\n\n"
                        + "• Decompiler: Vineflower\n"
                        + "• Compiler: javac (from the running JDK)\n"
                        + "• Modified jars are always written to a new file; every change is recorded with the\n"
                        + "   original/new binaries and sources in META-INF/rejar/ so it can be reverted.\n\n"
                        + "Shortcuts: Ctrl+O open, Ctrl+N go to class, Ctrl+Shift+F search, Ctrl+F find,\n"
                        + "Ctrl+G go to line, Ctrl+E edit, Ctrl+S compile & apply, Ctrl+Shift+S save new jar,\n"
                        + "Ctrl+click navigate to class."));
        Menu help = new Menu("_Help", null, about);
        return new MenuBar(file, navigate, tools, help);
    }

    private void showAbout(String text) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION, text);
        alert.initOwner(stage);
        alert.setTitle("About ReJar");
        alert.setHeaderText("ReJar");
        alert.setGraphic(Icons.view(64));
        alert.showAndWait();
    }

    private static MenuItem item(String text, String accelerator, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setMnemonicParsing(true);
        if (accelerator != null) {
            item.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        item.setOnAction(e -> action.run());
        return item;
    }

    private void rebuildRecentMenu() {
        recentMenu.getItems().clear();
        List<Path> recent = Settings.recentFiles();
        for (Path p : recent) {
            MenuItem mi = new MenuItem(p.toString());
            mi.setMnemonicParsing(false);
            mi.setOnAction(e -> openJar(p));
            recentMenu.getItems().add(mi);
        }
        if (recent.isEmpty()) {
            MenuItem none = new MenuItem("(empty)");
            none.setDisable(true);
            recentMenu.getItems().add(none);
        } else {
            recentMenu.getItems().add(new SeparatorMenuItem());
            MenuItem clear = new MenuItem("Clear");
            clear.setOnAction(e -> {
                Settings.clearRecentFiles();
                rebuildRecentMenu();
            });
            recentMenu.getItems().add(clear);
        }
    }

    private void withJar(java.util.function.Consumer<JarTab> action) {
        Tab tab = jarTabs.getSelectionModel().getSelectedItem();
        if (tab instanceof JarTab jarTab) {
            action.accept(jarTab);
        } else {
            setStatus("No jar opened");
        }
    }

    private void chooseAndOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open JAR");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Java archives", "*.jar", "*.war", "*.ear", "*.zip", "*.jmod"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File dir = Settings.lastDirectory();
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null) {
            for (File f : files) {
                Settings.setLastDirectory(f.getParentFile());
                openJar(f.toPath());
            }
        }
    }

    /** Opens a jar in a new tab (or selects its tab if already opened). */
    public void openJar(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        for (JarTab tab : jarTabs()) {
            if (tab.model().file().equals(abs)) {
                jarTabs.getSelectionModel().select(tab);
                return;
            }
        }
        if (!Files.isRegularFile(abs)) {
            Fx.warn(stage, "Cannot open jar", "File not found: " + abs);
            return;
        }
        busy("Opening " + abs.getFileName() + "…");
        Fx.run(() -> JarModel.open(abs), model -> {
            idle("Opened " + abs);
            JarTab tab = new JarTab(this, model);
            jarTabs.getTabs().add(tab);
            jarTabs.getSelectionModel().select(tab);
            Settings.addRecentFile(abs);
            rebuildRecentMenu();
        }, error -> {
            idle("Failed to open " + abs.getFileName());
            Fx.error(stage, "Cannot open " + abs.getFileName(), error);
        });
    }

    void fileSaved(Path path) {
        Settings.addRecentFile(path);
        rebuildRecentMenu();
    }

    private void closeJarTab(JarTab tab) {
        if (confirmDiscard(List.of(tab), "Close " + tab.model().name())) {
            jarTabs.getTabs().remove(tab);
            tab.dispose();
        }
    }

    /** Asks before losing pending (unsaved) changes. */
    boolean confirmDiscard(List<JarTab> tabs, String action) {
        List<String> dirty = new ArrayList<>();
        for (JarTab tab : tabs) {
            if (tab.hasUnsavedWork()) {
                dirty.add(tab.model().name());
            }
        }
        return dirty.isEmpty() || Fx.confirm(stage, action,
                "Pending changes are not saved in a new jar yet:\n  " + String.join("\n  ", dirty)
                        + "\n\nDiscard them?");
    }

    List<JarTab> jarTabs() {
        return jarTabs.getTabs().stream().filter(JarTab.class::isInstance).map(JarTab.class::cast).toList();
    }

    private void openSettings() {
        if (new SettingsDialog(stage).showAndWait().orElse(false)) {
            applyTheme();
            jarTabs().forEach(JarTab::applyFontSize);
        }
    }

    private void applyTheme() {
        scene.getStylesheets().clear();
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("rejar.css")).toExternalForm());
        if (Settings.darkTheme()) {
            scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("rejar-dark.css")).toExternalForm());
        }
    }

    // ----------------------------------------------------------- status

    public void setStatus(String text) {
        Fx.runLater(() -> status.setText(text));
    }

    public void busy(String text) {
        Fx.runLater(() -> {
            status.setText(text);
            progress.setVisible(true);
            progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        });
    }

    public void idle(String text) {
        Fx.runLater(() -> {
            status.setText(text);
            progress.setVisible(false);
        });
    }

    JarTab selectedJarTab() {
        Tab tab = jarTabs.getSelectionModel().getSelectedItem();
        return tab instanceof JarTab jt ? jt : null;
    }

    void selectJarTab(JarTab tab) {
        jarTabs.getSelectionModel().select(tab);
    }

    TabPane tabPane() {
        return jarTabs;
    }

    public void dispose() {
        for (JarTab tab : jarTabs()) {
            tab.dispose();
        }
    }
}
