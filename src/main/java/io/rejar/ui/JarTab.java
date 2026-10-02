package io.rejar.ui;

import io.rejar.core.ClassNames;
import io.rejar.core.ClassUnit;
import io.rejar.core.ClasspathBuilder;
import io.rejar.core.ClasspathConfig;
import io.rejar.core.DecompilerService;
import io.rejar.core.History;
import io.rejar.core.JarModel;
import io.rejar.core.JarWriter;
import io.rejar.core.MavenResolver;
import io.rejar.core.SearchService;
import io.rejar.core.SourceCompiler;
import io.rejar.core.SourceJarWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Tab of one opened jar: entry tree, editors, search, pending changes/history and console. */
final class JarTab extends Tab {

    private static final KeyCombination CLOSE_EDITOR = new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN);
    private static final int CONSOLE_LIMIT = 400_000;

    private final MainWindow main;
    private JarModel model;
    private DecompilerService decompiler;
    private ClasspathBuilder classpathBuilder;
    private ClasspathConfig classpathConfig;
    private ClasspathBuilder.Lookup libraryLookup;
    private boolean mavenFailed;

    private final EntryTree tree;
    private final TabPane editors = new TabPane();
    private final TabPane bottom = new TabPane();
    private final Tab searchTab;
    private final Tab changesTab;
    private final Tab consoleTab;
    private final SearchPane searchPane;
    private final ChangesPane changesPane;
    private final TextArea console = new TextArea();
    private final SplitPane vertical;
    private final Label summary = new Label();
    private final Label pendingLabel = new Label();
    private final Button saveButton = new Button("Save as New JAR…");
    private final Button sourceJarButton = new Button("Create Source JAR…");
    private final ProgressBar sourceJarProgress = new ProgressBar(0);
    private final Label sourceJarLabel = new Label();
    private final Button sourceJarCancel = new Button("Cancel");
    private final HBox sourceJarStatus = new HBox(6, sourceJarProgress, sourceJarLabel, sourceJarCancel);
    /** Cancel flag of the running source jar creation, null when none is running. */
    private AtomicBoolean sourceJarRun;
    private final VBox welcome = new VBox(8);
    private final Consumer<Set<String>> modelListener = paths -> Fx.runLater(() -> modelChanged(paths));
    private Map<String, ClassUnit> fqcnIndex;
    private long fqcnIndexRevision = -1;
    private int compareCounter;

    JarTab(MainWindow main, JarModel model) {
        this.main = main;
        init(model);
        classpathConfig = Settings.loadClasspath(model.file());
        if (classpathConfig == null) {
            classpathConfig = new ClasspathConfig();
            classpathConfig.embeddedPom = guessPom(model);
        }
        updateDecompilerLibrariesIfReady();

        tree = new EntryTree(this);
        searchPane = new SearchPane(this);
        changesPane = new ChangesPane(this);
        console.setEditable(false);
        console.getStyleClass().add("console");
        console.setStyle("-fx-font-family: \"" + Fx.monospaceFamily() + "\";");

        searchTab = new Tab("Search", searchPane);
        changesTab = new Tab("Changes & History", changesPane);
        consoleTab = new Tab("Console", console);
        bottom.getTabs().addAll(searchTab, changesTab, consoleTab);
        bottom.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        bottom.getStyleClass().add("bottom-tabs");

        editors.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        editors.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        editors.getStyleClass().add("editor-tabs");
        welcome.setAlignment(Pos.CENTER);
        welcome.getStyleClass().add("welcome");
        welcome.setMouseTransparent(true);
        welcome.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(editors.getTabs()));
        StackPane editorArea = new StackPane(editors, welcome);

        vertical = new SplitPane(editorArea, bottom);
        vertical.setOrientation(Orientation.VERTICAL);
        vertical.setDividerPositions(0.7);
        SplitPane horizontal = new SplitPane(tree, vertical);
        horizontal.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(tree, false);

        saveButton.getStyleClass().add("accent");
        saveButton.setTooltip(new Tooltip("Write the pending changes to a new jar (Ctrl+Shift+S)"));
        saveButton.setOnAction(e -> saveAsNewJar());
        Button gotoClass = new Button("Go to Class…");
        gotoClass.setTooltip(new Tooltip("Ctrl+N"));
        gotoClass.setOnAction(e -> gotoClass());
        Button search = new Button("Search…");
        search.setTooltip(new Tooltip("Search in decompiled classes and resources (Ctrl+Shift+F)"));
        search.setOnAction(e -> showSearch());
        Button classpath = new Button("Classpath…");
        classpath.setTooltip(new Tooltip("Compilation classpath: jar content, nested jars, Maven dependencies, custom jars"));
        classpath.setOnAction(e -> editClasspath());
        sourceJarButton.setTooltip(new Tooltip("Decompile every class into a -sources.jar of .java files"));
        sourceJarButton.setOnAction(e -> createSourceJar());
        sourceJarProgress.setPrefWidth(140);
        sourceJarLabel.getStyleClass().add("editor-info");
        sourceJarCancel.setOnAction(e -> cancelSourceJar());
        sourceJarStatus.setAlignment(Pos.CENTER_LEFT);
        sourceJarStatus.setVisible(false);
        sourceJarStatus.managedProperty().bind(sourceJarStatus.visibleProperty());
        pendingLabel.getStyleClass().add("pending-label");
        pendingLabel.setOnMouseClicked(e -> showChanges());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        summary.getStyleClass().add("editor-info");
        ToolBar toolbar = new ToolBar(saveButton, pendingLabel, new Separator(), gotoClass, search, new Separator(),
                classpath, sourceJarButton, sourceJarStatus, spacer, summary);

        BorderPane root = new BorderPane(horizontal);
        root.setTop(toolbar);
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (CLOSE_EDITOR.match(e)) {
                Tab selected = editors.getSelectionModel().getSelectedItem();
                if (selected != null && confirmCloseEditor(selected)) {
                    editors.getTabs().remove(selected);
                }
                e.consume();
            }
        });
        setContent(root);
        setOnCloseRequest(e -> {
            if (!main.confirmDiscard(List.of(this), "Close " + this.model.name())) {
                e.consume();
            }
        });
        setOnClosed(e -> dispose());
        updateTitle();
        updateSummary();
        log("Opened " + model.file());
        if (!model.history().changeSets.isEmpty()) {
            log("This jar was modified by ReJar: " + model.history().changeSets.size()
                    + " change set(s) recorded (see 'Changes & History').");
        }
    }

    private void init(JarModel newModel) {
        if (model != null) {
            model.removeChangeListener(modelListener);
        }
        this.model = newModel;
        this.decompiler = new DecompilerService(newModel);
        this.classpathBuilder = new ClasspathBuilder(newModel);
        newModel.addChangeListener(modelListener);
        fqcnIndex = null;
    }

    // ------------------------------------------------------------------ accessors

    JarModel model() {
        return model;
    }

    DecompilerService decompiler() {
        return decompiler;
    }

    MainWindow main() {
        return main;
    }

    ClasspathConfig classpathConfig() {
        return classpathConfig;
    }

    boolean hasUnsavedWork() {
        return model.hasPendingChanges() || editorTabs().stream().anyMatch(JarTab::isDirty);
    }

    private static boolean isDirty(Tab tab) {
        return (tab instanceof ClassEditorTab c && c.isDirty()) || (tab instanceof ResourceTab r && r.isDirty());
    }

    private List<Tab> editorTabs() {
        return new ArrayList<>(editors.getTabs());
    }

    // ------------------------------------------------------------------ model events

    private void modelChanged(Set<String> paths) {
        tree.rebuild();
        changesPane.refresh();
        for (Tab tab : editorTabs()) {
            if (tab instanceof ClassEditorTab c) {
                c.modelChanged(paths);
            } else if (tab instanceof ResourceTab r) {
                r.modelChanged(paths);
            }
        }
        updateTitle();
        updateSummary();
    }

    private void updateTitle() {
        int pending = model.pendingList().size();
        setText((pending > 0 ? "*" : "") + model.name());
        setTooltip(new Tooltip(model.file().toString()));
        pendingLabel.setText(pending == 0 ? "No pending change" : pending + " pending change(s)");
        pendingLabel.getStyleClass().remove("has-pending");
        if (pending > 0) {
            pendingLabel.getStyleClass().add("has-pending");
        }
        saveButton.setDisable(pending == 0);
    }

    private void updateSummary() {
        Map<String, ClassUnit> units = model.units();
        long resources = model.entryNames().stream().filter(n -> !n.endsWith("/") && !ClassNames.isClass(n)).count();
        List<String> parts = new ArrayList<>();
        parts.add(units.size() + " classes");
        parts.add(resources + " resources");
        if (!model.history().changeSets.isEmpty()) {
            parts.add("history: " + model.history().changeSets.size() + " change set(s)");
        }
        if (model.isSigned()) {
            parts.add("signed");
        }
        summary.setText(String.join("  ·  ", parts));

        welcome.getChildren().clear();
        Label title = new Label(model.name());
        title.getStyleClass().add("welcome-title");
        welcome.getChildren().add(title);
        List<String> lines = new ArrayList<>();
        lines.add(model.file().getParent() == null ? "" : model.file().getParent().toString());
        lines.add(units.size() + " classes, " + resources + " resources");
        try {
            byte[] mf = model.read("META-INF/MANIFEST.MF");
            if (mf != null) {
                Attributes attrs = new Manifest(new ByteArrayInputStream(mf)).getMainAttributes();
                for (String key : List.of("Main-Class", "Start-Class", "Implementation-Title", "Implementation-Version",
                        "Created-By", "Build-Jdk-Spec", "Spring-Boot-Version", "Multi-Release")) {
                    String value = attrs.getValue(key);
                    if (value != null) {
                        lines.add(key + ": " + value);
                    }
                }
            }
        } catch (IOException ignored) {
            // no manifest details
        }
        if (!model.embeddedPoms().isEmpty()) {
            lines.add("Maven descriptor: " + model.embeddedPoms().get(0) + (model.embeddedPoms().size() > 1
                    ? " (+" + (model.embeddedPoms().size() - 1) + ")" : ""));
        }
        if (!model.history().changeSets.isEmpty()) {
            lines.add("Modified by ReJar: " + model.history().changeSets.size() + " change set(s)");
        }
        lines.add("");
        lines.add("Double-click a class to decompile it · Ctrl+N go to class · Ctrl+Shift+F search");
        for (String line : lines) {
            Label l = new Label(line);
            l.getStyleClass().add("welcome-line");
            welcome.getChildren().add(l);
        }
    }

    // ------------------------------------------------------------------ opening entries

    void openEntry(String path) {
        if (ClassNames.isClass(path)) {
            ClassUnit unit = model.unitForEntry(path);
            if (unit != null) {
                openUnit(unit);
            }
        } else if (path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            openNestedJar(path);
        } else {
            openResource(path);
        }
        tree.select(path, true);
    }

    ClassEditorTab openUnitById(String unitId) {
        ClassUnit unit = model.unit(unitId);
        if (unit == null) {
            main.setStatus("Class not found: " + ClassNames.toFqcn(unitId));
            return null;
        }
        tree.select(unit.outerEntry(), true);
        return openUnit(unit);
    }

    ClassEditorTab openUnit(ClassUnit unit) {
        for (Tab tab : editors.getTabs()) {
            if (tab instanceof ClassEditorTab c && c.unitId().equals(unit.id())) {
                editors.getSelectionModel().select(tab);
                return c;
            }
        }
        ClassEditorTab tab = new ClassEditorTab(this, unit);
        tab.setOnCloseRequest(e -> {
            if (!confirmCloseEditor(tab)) {
                e.consume();
            }
        });
        editors.getTabs().add(tab);
        editors.getSelectionModel().select(tab);
        return tab;
    }

    ResourceTab openResource(String path) {
        for (Tab tab : editors.getTabs()) {
            if (tab instanceof ResourceTab r && r.path().equals(path)) {
                editors.getSelectionModel().select(tab);
                return r;
            }
        }
        ResourceTab tab = new ResourceTab(this, path);
        tab.setOnCloseRequest(e -> {
            if (!confirmCloseEditor(tab)) {
                e.consume();
            }
        });
        editors.getTabs().add(tab);
        editors.getSelectionModel().select(tab);
        return tab;
    }

    private boolean confirmCloseEditor(Tab tab) {
        return !isDirty(tab) || Fx.confirm(main.stage(), "Close " + tab.getText(),
                "The editor contains edits that were not applied. Close it anyway?");
    }

    void openBytecode(String unitId) {
        ClassEditorTab tab = openUnitById(unitId);
        if (tab != null) {
            tab.showBytecode();
        }
    }

    void editUnit(String unitId) {
        ClassEditorTab tab = openUnitById(unitId);
        if (tab != null) {
            tab.startEditing();
        }
    }

    void openHit(SearchService.Hit hit) {
        switch (hit.kind()) {
            case CLASS -> {
                ClassEditorTab tab = openUnitById(hit.path());
                if (tab != null) {
                    tab.goTo(hit.line(), hit.column(), hit.length());
                }
            }
            case RESOURCE -> {
                openResource(hit.path()).goTo(hit.line(), hit.column(), hit.length());
                tree.select(hit.path(), true);
            }
            case NAME -> openEntry(hit.path());
        }
    }

    void openCompare(String title, String left, String right, String leftTitle, String rightTitle) {
        CompareTab tab = new CompareTab(title, left, right, leftTitle, rightTitle);
        tab.setUserData("compare-" + (++compareCounter));
        editors.getTabs().add(tab);
        editors.getSelectionModel().select(tab);
    }

    void openNestedJar(String path) {
        Fx.run(() -> {
            byte[] bytes = model.read(path);
            if (bytes == null) {
                throw new IOException("Entry not found: " + path);
            }
            Path dir = model.workDir().resolve("opened").resolve(Integer.toHexString(path.hashCode()));
            Files.createDirectories(dir);
            Path file = dir.resolve(path.substring(path.lastIndexOf('/') + 1));
            Files.write(file, bytes);
            return file;
        }, file -> {
            log("Nested jar " + path + " extracted to " + file
                    + " (a modified copy has to be saved as a new jar and put back with 'Replace with File')");
            main.openJar(file);
        }, error -> Fx.error(main.stage(), "Cannot open nested jar", error));
    }

    // ------------------------------------------------------------------ navigation

    void gotoClass() {
        new GotoClassDialog(main.stage(), model.units().values(), "").showAndWait().ifPresent(this::openUnit);
    }

    void showSearch() {
        bottom.getSelectionModel().select(searchTab);
        ensureBottomVisible();
        Tab current = editors.getSelectionModel().getSelectedItem();
        if (current instanceof ClassEditorTab c) {
            String sel = c.currentEditor().area().getSelectedText();
            if (!sel.isEmpty() && !sel.contains("\n")) {
                searchPane.setQuery(sel);
            }
        }
        searchPane.focus();
    }

    void showChanges() {
        bottom.getSelectionModel().select(changesTab);
        ensureBottomVisible();
    }

    void findInCurrentEditor() {
        Tab current = editors.getSelectionModel().getSelectedItem();
        if (current instanceof ClassEditorTab c) {
            c.currentEditor().showFind();
        } else if (current instanceof ResourceTab r) {
            r.editor().showFind();
        } else {
            tree.focusFilter();
        }
    }

    private void ensureBottomVisible() {
        if (vertical.getDividerPositions()[0] > 0.9) {
            vertical.setDividerPositions(0.65);
        }
    }

    /** Ctrl+click on an identifier: opens the corresponding class of the jar. */
    void navigate(String word, String sourceText) {
        String cleaned = word.replaceAll("^\\.+|\\.+$", "");
        if (cleaned.isEmpty()) {
            return;
        }
        String[] parts = cleaned.split("\\.");
        Map<String, ClassUnit> index = fqcnIndex();
        // fully qualified (possibly followed by nested class / member names)
        for (int n = parts.length; n >= 2; n--) {
            String candidate = String.join(".", Arrays.copyOf(parts, n));
            ClassUnit unit = index.get(candidate);
            if (unit != null) {
                openAndReveal(unit, n < parts.length ? parts[n] : null);
                return;
            }
        }
        String simple = parts[0];
        if (simple.isEmpty() || !Character.isUpperCase(simple.charAt(0))) {
            // lower case start: maybe a qualified name whose package is not in the jar
            main.setStatus("No class of the jar matches '" + cleaned + "'");
            return;
        }
        String nested = parts.length > 1 ? parts[1] : null;
        // declared in the current source (nested class)
        Pattern declaration = declarationPattern(simple);
        Tab current = editors.getSelectionModel().getSelectedItem();
        if (sourceText != null && declaration.matcher(sourceText).find() && current instanceof ClassEditorTab c) {
            c.reveal(declaration);
            return;
        }
        List<String> candidates = new ArrayList<>();
        if (sourceText != null) {
            Matcher imp = Pattern.compile("^\\s*import\\s+([\\w.]+)\\." + Pattern.quote(simple) + "\\s*;", Pattern.MULTILINE)
                    .matcher(sourceText);
            if (imp.find()) {
                candidates.add(imp.group(1) + "." + simple);
                candidates.add(imp.group(1)); // import of a nested class: Outer.Simple
            }
            Matcher pkg = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE).matcher(sourceText);
            candidates.add(pkg.find() ? pkg.group(1) + "." + simple : simple);
            Matcher wildcard = Pattern.compile("^\\s*import\\s+([\\w.]+)\\.\\*\\s*;", Pattern.MULTILINE).matcher(sourceText);
            while (wildcard.find()) {
                candidates.add(wildcard.group(1) + "." + simple);
            }
        }
        for (String candidate : candidates) {
            ClassUnit unit = index.get(candidate);
            if (unit != null) {
                openAndReveal(unit, unit.simpleName().equals(simple) ? nested : simple);
                return;
            }
        }
        List<ClassUnit> bySimpleName = model.units().values().stream().filter(u -> u.simpleName().equals(simple)).toList();
        if (bySimpleName.size() == 1) {
            openAndReveal(bySimpleName.get(0), nested);
        } else if (bySimpleName.size() > 1) {
            new GotoClassDialog(main.stage(), bySimpleName, simple).showAndWait().ifPresent(u -> openAndReveal(u, nested));
        } else {
            main.setStatus(simple + " is not a class of this jar (JDK or dependency class)");
        }
    }

    private void openAndReveal(ClassUnit unit, String member) {
        ClassEditorTab tab = openUnit(unit);
        tree.select(unit.outerEntry(), true);
        if (member != null && !member.isEmpty()) {
            Pattern p = Character.isUpperCase(member.charAt(0))
                    ? declarationPattern(member)
                    : Pattern.compile("\\b(" + Pattern.quote(member) + ")\\s*[(=;]");
            tab.reveal(p);
        } else {
            tab.reveal(declarationPattern(unit.simpleName()));
        }
    }

    private static Pattern declarationPattern(String simpleName) {
        return Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+(" + Pattern.quote(simpleName) + ")\\b");
    }

    private Map<String, ClassUnit> fqcnIndex() {
        if (fqcnIndex == null || fqcnIndexRevision != model.revision()) {
            Map<String, ClassUnit> index = new HashMap<>();
            for (ClassUnit unit : model.units().values()) {
                // prefer the base version over multi-release variants
                index.merge(unit.fqcn(), unit, (a, b) -> a.prefix().startsWith("META-INF/versions/") ? b : a);
            }
            fqcnIndex = index;
            fqcnIndexRevision = model.revision();
        }
        return fqcnIndex;
    }

    // ------------------------------------------------------------------ compilation

    void compileAndApply(ClassEditorTab tab) {
        ClassUnit unit = model.unit(tab.unitId());
        if (unit == null) {
            Fx.warn(main.stage(), "Cannot compile", "The class does not exist in the jar anymore.");
            return;
        }
        if (!SourceCompiler.isAvailable()) {
            Fx.warn(main.stage(), "Compiler not available", "ReJar runs on a JRE: start it with a JDK to compile.");
            return;
        }
        String source = tab.sourceText();
        String original = tab.originalSource();
        JarModel m = model;
        ClasspathConfig cfg = classpathConfig.copy();
        boolean needMaven = cfg.useMaven && cfg.hasPomSource() && cfg.mavenClasspath == null && !mavenFailed;
        tab.compilationStarted();
        main.busy("Compiling " + unit.fqcn() + "…");

        record Outcome(SourceCompiler.Result result, int release, List<Path> deps, boolean mavenFailed) {
        }
        Fx.run(() -> {
            List<Path> deps = null;
            boolean failed = false;
            if (needMaven) {
                logAsync("Resolving Maven dependencies of " + cfg.pomLabel() + "…");
                try {
                    deps = resolveMavenBlocking(cfg, this::logAsync, new AtomicBoolean());
                    cfg.mavenClasspath = deps;
                } catch (Exception e) {
                    failed = true;
                    logAsync("Maven resolution failed: " + e.getMessage()
                            + "\n  -> compiling without Maven dependencies (see Classpath… / Tools › Settings)");
                }
            }
            List<Path> classpath = classpathBuilder.build(cfg);
            int release = cfg.release > 0 ? cfg.release
                    : ClassNames.releaseOf(ClassNames.majorVersion(m.read(unit.outerEntry())));
            List<String> options = cfg.javacOptions == null || cfg.javacOptions.isBlank()
                    ? List.of() : Arrays.asList(cfg.javacOptions.trim().split("\\s+"));
            logAsync("Compiling " + unit.fqcn() + " with --release " + release + " (" + classpath.size()
                    + " classpath entries)");
            SourceCompiler.Result result = new SourceCompiler().compile(unit.className(), source, classpath, release, options);
            return new Outcome(result, release, deps, failed);
        }, outcome -> {
            if (outcome.deps() != null) {
                classpathConfig.mavenClasspath = outcome.deps();
                Settings.saveClasspath(model.file(), classpathConfig);
                updateDecompilerLibrariesIfReady();
            }
            if (outcome.mavenFailed()) {
                mavenFailed = true;
            }
            SourceCompiler.Result result = outcome.result();
            for (SourceCompiler.Problem p : result.problems()) {
                log("  " + p);
            }
            if (result.success() && model == m) {
                ClassUnit current = model.unit(unit.id());
                model.applyCompiled(current != null ? current : unit, result.classes(), source, original, outcome.release());
                log("Applied " + String.join(", ", result.classes().keySet()) + " (pending, not saved)");
                main.idle("Compiled and applied " + unit.fqcn() + " – use 'Save as New JAR' to write a new jar");
            } else {
                main.idle("Compilation of " + unit.fqcn() + " failed");
            }
            tab.compilationFinished(result, source, outcome.release());
        }, error -> {
            tab.compilationError();
            main.idle("Compilation failed");
            Fx.error(main.stage(), "Compilation failed", error);
        });
    }

    /** Runs Maven for the pom of the given configuration (blocking, call from a background thread). */
    List<Path> resolveMavenBlocking(ClasspathConfig cfg, Consumer<String> log, AtomicBoolean cancel) throws Exception {
        Path mvn = MavenResolver.findMaven(Settings.mavenExecutable()).orElseThrow(() -> new IOException(
                "Maven executable not found: set it in Tools › Settings (or MAVEN_HOME / PATH)"));
        Path pom = classpathBuilder.pomFile(cfg);
        return MavenResolver.resolve(mvn, pom, Settings.mavenArgs(), log, cancel);
    }

    void editClasspath() {
        new ClasspathDialog(this).showAndWait().ifPresent(cfg -> {
            classpathConfig = cfg;
            mavenFailed = false;
            Settings.saveClasspath(model.file(), cfg);
            updateDecompilerLibrariesIfReady();
            log("Classpath updated: nested jars " + (cfg.includeNestedJars ? "on" : "off") + ", Maven "
                    + (cfg.useMaven ? (cfg.mavenClasspath == null ? "not resolved" : cfg.mavenClasspath.size() + " deps") : "off")
                    + ", " + cfg.customEntries.size() + " custom entr(ies)");
        });
    }

    /** Lets the decompiler resolve types from the dependencies (better generics/overrides). */
    private void updateDecompilerLibrariesIfReady() {
        ClasspathConfig cfg = classpathConfig;
        if (cfg == null) {
            return;
        }
        boolean hasLibs = !cfg.customEntries.isEmpty() || (cfg.useMaven && cfg.mavenClasspath != null);
        if (!hasLibs) {
            return;
        }
        ClasspathBuilder builder = classpathBuilder;
        DecompilerService target = decompiler;
        Fx.run(() -> {
            ClasspathConfig c = cfg.copy();
            c.includeNestedJars = false; // avoid extracting nested jars just for decompiling
            return new ClasspathBuilder.Lookup(builder.libraries(c));
        }, lookup -> {
            if (libraryLookup != null) {
                libraryLookup.close();
            }
            libraryLookup = lookup;
            target.setExtraLibraries(lookup);
        }, error -> log("Cannot prepare libraries for the decompiler: " + error.getMessage()));
    }

    private static String guessPom(JarModel model) {
        List<String> poms = model.embeddedPoms();
        if (poms.size() == 1) {
            return poms.get(0);
        }
        String name = model.name().toLowerCase(Locale.ROOT);
        String best = null;
        int bestLength = -1;
        for (String pom : poms) {
            String[] parts = pom.split("/");
            if (parts.length >= 5) {
                String artifactId = parts[3].toLowerCase(Locale.ROOT);
                if ((name.startsWith(artifactId + "-") || name.startsWith(artifactId + ".")) && artifactId.length() > bestLength) {
                    best = pom;
                    bestLength = artifactId.length();
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ saving / reverting

    void saveAsNewJar() {
        if (!model.hasPendingChanges()) {
            Fx.info(main.stage(), "Nothing to save",
                    "There is no pending change. Edit a class ('Edit' then 'Compile & Apply') or a resource first.");
            return;
        }
        long dirty = editorTabs().stream().filter(JarTab::isDirty).count();
        if (dirty > 0 && !Fx.confirm(main.stage(), "Unapplied edits",
                dirty + " editor(s) contain edits that were not compiled/applied: they will not be part of the new jar.\n"
                        + "Continue?")) {
            return;
        }
        new SaveDialog(this).showAndWait().ifPresent(request -> {
            main.busy("Writing " + request.target().getFileName() + "…");
            JarModel m = model;
            Fx.run(() -> JarWriter.write(m, request.target(), request.description()), result -> {
                log("Saved " + result.target() + " – change set " + result.changeSet().id + " ("
                        + result.changeSet().entries.size() + " entr(ies), " + result.changeSet().sources.size()
                        + " source(s))" + (result.signatureRemoved() ? " – signature removed" : ""));
                main.fileSaved(result.target());
                main.idle("Saved " + result.target());
                if (request.openSaved()) {
                    reload(result.target());
                } else {
                    Fx.info(main.stage(), "New jar written", result.target() + "\n\nChange set " + result.changeSet().id
                            + " recorded in META-INF/rejar/history.json.");
                }
            }, error -> {
                main.idle("Save failed");
                Fx.error(main.stage(), "Cannot write the new jar", error);
            });
        });
    }

    /** Replaces the jar of this tab by another one (typically the jar just written). */
    private void reload(Path file) {
        List<String> openUnits = new ArrayList<>();
        List<String> openResources = new ArrayList<>();
        for (Tab tab : editorTabs()) {
            if (tab instanceof ClassEditorTab c) {
                openUnits.add(c.unitId());
            } else if (tab instanceof ResourceTab r) {
                openResources.add(r.path());
            }
        }
        searchPane.cancel();
        cancelSourceJar();
        Fx.run(() -> JarModel.open(file), newModel -> {
            JarModel old = model;
            editors.getTabs().clear();
            init(newModel);
            mavenFailed = false;
            Settings.saveClasspath(newModel.file(), classpathConfig);
            updateDecompilerLibrariesIfReady();
            tree.rebuild();
            changesPane.refresh();
            updateTitle();
            updateSummary();
            openUnits.forEach(id -> {
                if (model.unit(id) != null) {
                    openUnit(model.unit(id));
                }
            });
            openResources.forEach(p -> {
                if (model.exists(p)) {
                    openResource(p);
                }
            });
            log("Now working on " + file);
            Fx.background(() -> {
                try {
                    old.close();
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }, error -> Fx.error(main.stage(), "Cannot open " + file, error));
    }

    void revert(Map<History.ChangeSet, List<History.ChangeEntry>> selection) {
        int count = selection.values().stream().mapToInt(List::size).sum();
        List<String> ids = selection.keySet().stream().map(cs -> cs.id).toList();
        if (!Fx.confirm(main.stage(), "Revert " + String.join(", ", ids),
                "Stage the revert of " + count + " entr" + (count == 1 ? "y" : "ies")
                        + ": original bytes stored in the jar history are restored (added entries are removed).\n\n"
                        + "The result is written with 'Save as New JAR'.")) {
            return;
        }
        List<String> conflicts = new ArrayList<>();
        try {
            for (Map.Entry<History.ChangeSet, List<History.ChangeEntry>> e : selection.entrySet()) {
                conflicts.addAll(model.stageRevert(e.getKey(), e.getValue()));
            }
        } catch (IOException ex) {
            Fx.error(main.stage(), "Revert failed", ex);
            return;
        }
        log("Staged revert of " + String.join(", ", ids) + " (" + count + " entries)");
        showChanges();
        if (!conflicts.isEmpty()) {
            Fx.warn(main.stage(), "Entries changed again later",
                    "These entries were modified again after the reverted change set; the state from before it was "
                            + "restored anyway:\n  " + String.join("\n  ", new LinkedHashSet<>(conflicts)));
        }
        main.setStatus("Revert staged – use 'Save as New JAR' to write it");
    }

    // ------------------------------------------------------------------ entry operations

    void extract(String path) {
        extractAs(path, path.substring(path.lastIndexOf('/') + 1));
    }

    void extractAs(String path, String suggestedName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Extract " + path);
        chooser.setInitialFileName(suggestedName.endsWith(".bin") ? suggestedName.substring(0, suggestedName.length() - 4) : suggestedName);
        File dir = Settings.lastDirectory();
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
        File file = chooser.showSaveDialog(main.stage());
        if (file == null) {
            return;
        }
        Settings.setLastDirectory(file.getParentFile());
        Fx.run(() -> {
            byte[] data = model.read(path);
            if (data == null) {
                throw new IOException("Entry not found: " + path);
            }
            Files.write(file.toPath(), data);
            return file;
        }, f -> main.setStatus("Extracted " + path + " to " + f), error -> Fx.error(main.stage(), "Extract failed", error));
    }

    void replaceWithFile(String path) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Replace " + path);
        File dir = Settings.lastDirectory();
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
        File file = chooser.showOpenDialog(main.stage());
        if (file == null) {
            return;
        }
        Settings.setLastDirectory(file.getParentFile());
        try {
            model.putResource(path, Files.readAllBytes(file.toPath()), "replaced by " + file.getName());
            log("Replaced " + path + " with " + file);
        } catch (IOException e) {
            Fx.error(main.stage(), "Cannot read " + file, e);
        }
    }

    void addFile(String folderPath) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add file to " + (folderPath.isEmpty() ? "the jar root" : folderPath));
        File dir = Settings.lastDirectory();
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
        List<File> files = chooser.showOpenMultipleDialog(main.stage());
        if (files == null) {
            return;
        }
        Settings.setLastDirectory(files.get(0).getParentFile());
        for (File file : files) {
            String path = folderPath + file.getName();
            if (model.exists(path) && !Fx.confirm(main.stage(), "Entry exists", path + " already exists. Replace it?")) {
                continue;
            }
            try {
                model.putResource(path, Files.readAllBytes(file.toPath()), "added from " + file.getName());
                log("Added " + path);
            } catch (IOException e) {
                Fx.error(main.stage(), "Cannot read " + file, e);
            }
        }
    }

    void deleteEntry(EntryTree.Node node) {
        List<String> paths = node.kind() == EntryTree.Kind.CLASS && model.unit(node.unitId()) != null
                ? model.unit(node.unitId()).entries()
                : List.of(node.path());
        if (Fx.confirm(main.stage(), "Delete " + node.label(), "Delete " + String.join(", ", paths)
                + " from the jar?\n(pending until 'Save as New JAR')")) {
            paths.forEach(model::deleteEntry);
        }
    }

    void discardEntry(EntryTree.Node node) {
        if (node.kind() == EntryTree.Kind.CLASS) {
            model.discardUnit(node.unitId());
        } else {
            model.discard(List.of(node.path()));
        }
    }

    /** Decompiles every class of the jar (pending edits included) into a {@code -sources.jar}. */
    void createSourceJar() {
        if (sourceJarRun != null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Create source JAR");
        chooser.setInitialFileName(SourceJarWriter.defaultName(model.name()));
        chooser.setInitialDirectory(model.file().getParent().toFile());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Source JAR", "*.jar", "*.zip"));
        File file = chooser.showSaveDialog(main.stage());
        if (file == null) {
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        sourceJarRun = cancel;
        sourceJarButton.setDisable(true);
        sourceJarCancel.setDisable(false);
        sourceJarProgress.setProgress(0);
        sourceJarLabel.setText("0 / " + model.units().size() + " classes");
        sourceJarStatus.setVisible(true);
        main.setStatus("Creating source JAR " + file.getName() + "…");
        JarModel m = model;
        DecompilerService dec = decompiler;
        // workers report every class: only the latest value is pushed to the FX thread
        AtomicInteger latest = new AtomicInteger();
        AtomicBoolean scheduled = new AtomicBoolean();
        Fx.run(() -> SourceJarWriter.write(m, dec, file.toPath(), (done, total) -> {
            latest.accumulateAndGet(done, Math::max);
            if (scheduled.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    scheduled.set(false);
                    if (sourceJarRun == cancel && !cancel.get()) {
                        sourceJarProgress.setProgress((double) latest.get() / total);
                        sourceJarLabel.setText(latest.get() + " / " + total + " classes");
                    }
                });
            }
        }, cancel), result -> {
            sourceJarFinished(cancel);
            if (result.cancelled()) {
                main.setStatus("Source JAR creation cancelled");
                log("Source jar creation cancelled: " + result.target() + " was not written");
            } else {
                main.setStatus("Source JAR written: " + result.target());
                log("Created source jar " + result.target() + " (" + result.sources() + " source files)");
            }
        }, error -> {
            sourceJarFinished(cancel);
            if (cancel.get()) {
                // the jar was closed while its classes were being read
                main.setStatus("Source JAR creation cancelled");
                return;
            }
            main.setStatus("Source JAR creation failed");
            Fx.error(main.stage(), "Cannot create the source JAR", error);
        });
    }

    private void cancelSourceJar() {
        if (sourceJarRun != null && sourceJarRun.compareAndSet(false, true)) {
            sourceJarCancel.setDisable(true);
            sourceJarProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            sourceJarLabel.setText("Cancelling…");
        }
    }

    private void sourceJarFinished(AtomicBoolean run) {
        if (sourceJarRun == run) {
            sourceJarRun = null;
            sourceJarButton.setDisable(false);
            sourceJarStatus.setVisible(false);
        }
    }

    // ------------------------------------------------------------------ misc

    void log(String line) {
        Fx.runLater(() -> {
            console.appendText(line + "\n");
            if (console.getLength() > CONSOLE_LIMIT) {
                console.deleteText(0, console.getLength() - CONSOLE_LIMIT / 2);
            }
        });
    }

    private void logAsync(String line) {
        log(line);
        if (line.contains("[ERROR]") || line.startsWith("Maven resolution failed")) {
            Fx.runLater(() -> bottom.getSelectionModel().select(consoleTab));
        }
    }

    void applyFontSize() {
        for (Tab tab : editorTabs()) {
            if (tab instanceof ClassEditorTab c) {
                c.applyFontSize();
            } else if (tab instanceof ResourceTab r) {
                r.applyFontSize();
            }
        }
    }

    void dispose() {
        searchPane.cancel();
        cancelSourceJar();
        model.removeChangeListener(modelListener);
        if (libraryLookup != null) {
            libraryLookup.close();
        }
        JarModel m = model;
        Fx.background(() -> {
            try {
                m.close();
            } catch (IOException ignored) {
                // best effort
            }
        });
    }
}
