package io.rejar.ui;

import io.rejar.core.ClassNames;
import io.rejar.core.ClassUnit;
import io.rejar.core.JarModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javafx.animation.PauseTransition;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Tree of the jar content: folders/packages, top-level classes (nested classes hidden) and resources. */
final class EntryTree extends VBox {

    enum Kind { ROOT, FOLDER, CLASS, RESOURCE, JAR }

    /** Tree node value. {@code path} is the entry path (folder path ending with '/' for folders). */
    record Node(Kind kind, String label, String path, String unitId) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final int AUTO_EXPAND_LIMIT = 4000;

    private final JarTab owner;
    private final TreeView<Node> tree = new TreeView<>();
    private final TextField filter = new TextField();
    private final PauseTransition filterDelay = new PauseTransition(Duration.millis(250));

    EntryTree(JarTab owner) {
        this.owner = owner;
        getStyleClass().add("entry-tree");
        filter.setPromptText("Filter entries (name or path)");
        filterDelay.setOnFinished(e -> rebuild());
        filter.textProperty().addListener((obs, o, n) -> filterDelay.playFromStart());
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                filter.clear();
            } else if (e.getCode() == KeyCode.DOWN) {
                tree.requestFocus();
                if (tree.getSelectionModel().isEmpty()) {
                    tree.getSelectionModel().selectFirst();
                }
            }
        });
        tree.setShowRoot(true);
        tree.setCellFactory(tv -> new EntryCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        getChildren().addAll(filter, tree);
        rebuild();
    }

    TreeView<Node> view() {
        return tree;
    }

    void focusFilter() {
        filter.requestFocus();
    }

    private void openSelected() {
        TreeItem<Node> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        Node node = item.getValue();
        switch (node.kind()) {
            case CLASS, RESOURCE -> owner.openEntry(node.path());
            case JAR -> owner.openNestedJar(node.path());
            default -> item.setExpanded(!item.isExpanded());
        }
    }

    /** Rebuilds the tree from the model, keeping expanded folders and the selection. */
    void rebuild() {
        JarModel model = owner.model();
        Set<String> expanded = new HashSet<>();
        if (tree.getRoot() != null) {
            collectExpanded(tree.getRoot(), expanded);
        }
        TreeItem<Node> selected = tree.getSelectionModel().getSelectedItem();
        String selectedPath = selected == null ? null : selected.getValue().path();

        String f = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        Folder root = new Folder("", "");
        int count = 0;
        for (String entry : model.entryNames()) {
            if (entry.endsWith("/")) {
                continue;
            }
            Node node;
            if (ClassNames.isClass(entry)) {
                ClassUnit unit = model.unitForEntry(entry);
                if (unit == null || !entry.equals(unit.outerEntry())) {
                    continue;
                }
                node = new Node(Kind.CLASS, unit.simpleName(), entry, unit.id());
                if (!f.isEmpty() && !entry.toLowerCase(Locale.ROOT).contains(f)
                        && !unit.fqcn().toLowerCase(Locale.ROOT).contains(f)) {
                    continue;
                }
            } else {
                if (!f.isEmpty() && !entry.toLowerCase(Locale.ROOT).contains(f)) {
                    continue;
                }
                String name = entry.substring(entry.lastIndexOf('/') + 1);
                node = new Node(name.toLowerCase(Locale.ROOT).endsWith(".jar") ? Kind.JAR : Kind.RESOURCE, name, entry, null);
            }
            root.add(entry, node);
            count++;
        }

        TreeItem<Node> rootItem = new TreeItem<>(new Node(Kind.ROOT, model.name(), "", null));
        rootItem.setExpanded(true);
        boolean expandAll = !f.isEmpty() && count <= AUTO_EXPAND_LIMIT;
        boolean firstBuild = tree.getRoot() == null;
        root.fill(rootItem, expanded, expandAll);
        if (firstBuild) {
            // expand single top-level folders so the content is visible right away
            TreeItem<Node> item = rootItem;
            while (item.getChildren().size() == 1 && item.getChildren().get(0).getValue().kind() == Kind.FOLDER) {
                item = item.getChildren().get(0);
                item.setExpanded(true);
            }
        }
        tree.setRoot(rootItem);
        if (selectedPath != null) {
            select(selectedPath, false);
        }
    }

    private static void collectExpanded(TreeItem<Node> item, Set<String> expanded) {
        if (item.isExpanded() && item.getValue().kind() == Kind.FOLDER) {
            expanded.add(item.getValue().path());
        }
        for (TreeItem<Node> child : item.getChildren()) {
            collectExpanded(child, expanded);
        }
    }

    /** Selects (and reveals) the node of an entry. */
    void select(String entryPath, boolean scroll) {
        TreeItem<Node> item = find(tree.getRoot(), entryPath);
        if (item != null) {
            for (TreeItem<Node> p = item.getParent(); p != null; p = p.getParent()) {
                p.setExpanded(true);
            }
            tree.getSelectionModel().select(item);
            if (scroll) {
                int row = tree.getRow(item);
                tree.scrollTo(Math.max(0, row - 5));
            }
        }
    }

    private static TreeItem<Node> find(TreeItem<Node> item, String path) {
        if (item == null) {
            return null;
        }
        Node node = item.getValue();
        if (node.path().equals(path) && node.kind() != Kind.ROOT) {
            return item;
        }
        if (node.kind() == Kind.ROOT || (node.kind() == Kind.FOLDER && path.startsWith(node.path()))) {
            for (TreeItem<Node> child : item.getChildren()) {
                TreeItem<Node> found = find(child, path);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    void refreshCells() {
        tree.refresh();
    }

    // --------------------------------------------------------------- building

    private static final class Folder {
        final String name;
        final String path;
        final Map<String, Folder> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final List<Node> files = new ArrayList<>();
        boolean hasClasses;

        Folder(String name, String path) {
            this.name = name;
            this.path = path;
        }

        void add(String entry, Node node) {
            String[] parts = entry.split("/");
            Folder folder = this;
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                p.append(parts[i]).append('/');
                String folderPath = p.toString();
                String part = parts[i];
                folder = folder.folders.computeIfAbsent(part, k -> new Folder(part, folderPath));
                if (node.kind() == Kind.CLASS) {
                    folder.hasClasses = true;
                }
            }
            folder.files.add(node);
        }

        void fill(TreeItem<Node> parent, Set<String> expanded, boolean expandAll) {
            for (Folder child : folders.values()) {
                // compact chains of single-child folders: com/acme/app -> com.acme.app
                Folder f = child;
                StringBuilder label = new StringBuilder(f.name);
                while (f.files.isEmpty() && f.folders.size() == 1) {
                    Folder next = f.folders.values().iterator().next();
                    boolean javaPackage = next.hasClasses && isIdentifier(f.name) && isIdentifier(next.name);
                    label.append(javaPackage ? '.' : '/').append(next.name);
                    f = next;
                }
                TreeItem<Node> item = new TreeItem<>(new Node(Kind.FOLDER, label.toString(), f.path, null));
                item.setExpanded(expandAll || expanded.contains(f.path));
                f.fill(item, expanded, expandAll);
                parent.getChildren().add(item);
            }
            files.sort(Comparator.comparing((Node n) -> n.kind() != Kind.CLASS)
                    .thenComparing(Node::label, String.CASE_INSENSITIVE_ORDER));
            for (Node file : files) {
                parent.getChildren().add(new TreeItem<>(file));
            }
        }

        private static boolean isIdentifier(String s) {
            if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
                return false;
            }
            for (int i = 1; i < s.length(); i++) {
                if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    // --------------------------------------------------------------- cells

    private final class EntryCell extends TreeCell<Node> {
        @Override
        protected void updateItem(Node node, boolean empty) {
            super.updateItem(node, empty);
            getStyleClass().removeAll("modified", "added");
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setTooltip(null);
                return;
            }
            JarModel model = owner.model();
            String text = node.label();
            boolean modified = false;
            boolean added = false;
            if (node.kind() == Kind.CLASS) {
                modified = model.isUnitModified(node.unitId());
                added = modified && !model.existsInOriginal(node.path());
            } else if (node.kind() == Kind.RESOURCE || node.kind() == Kind.JAR) {
                modified = model.isModified(node.path());
                added = modified && !model.existsInOriginal(node.path());
            }
            if (modified) {
                getStyleClass().add(added ? "added" : "modified");
                text += added ? "  [added]" : "  *";
            }
            setText(text);
            setGraphic(icon(node));
            setContextMenu(menu(node));
        }

        private javafx.scene.Node icon(Node node) {
            return switch (node.kind()) {
                case CLASS -> {
                    Label l = new Label("C");
                    l.getStyleClass().addAll("badge", "badge-class");
                    yield l;
                }
                case JAR -> {
                    Label l = new Label("J");
                    l.getStyleClass().addAll("badge", "badge-jar");
                    yield l;
                }
                case ROOT -> {
                    Label l = new Label("J");
                    l.getStyleClass().addAll("badge", "badge-root");
                    yield l;
                }
                case FOLDER -> shape("icon-folder");
                case RESOURCE -> shape("icon-file");
            };
        }

        private Region shape(String styleClass) {
            Region r = new Region();
            r.getStyleClass().addAll("icon", styleClass);
            return r;
        }

        private ContextMenu menu(Node node) {
            List<MenuItem> items = new ArrayList<>();
            switch (node.kind()) {
                case CLASS -> {
                    items.add(action("Open", () -> owner.openEntry(node.path())));
                    items.add(action("Open Bytecode", () -> owner.openBytecode(node.unitId())));
                    items.add(action("Edit Source", () -> owner.editUnit(node.unitId())));
                }
                case RESOURCE -> items.add(action("Open", () -> owner.openEntry(node.path())));
                case JAR -> items.add(action("Open Nested Jar in New Tab", () -> owner.openNestedJar(node.path())));
                default -> {
                }
            }
            if (node.kind() == Kind.ROOT || node.kind() == Kind.FOLDER) {
                items.add(action("Add File Here…", () -> owner.addFile(node.path())));
            }
            if (node.kind() != Kind.ROOT) {
                items.add(action("Copy Path", () -> {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(node.path());
                    Clipboard.getSystemClipboard().setContent(content);
                }));
            }
            if (node.kind() == Kind.CLASS || node.kind() == Kind.RESOURCE || node.kind() == Kind.JAR) {
                items.add(new SeparatorMenuItem());
                items.add(action("Extract…", () -> owner.extract(node.path())));
                if (node.kind() != Kind.CLASS) {
                    items.add(action("Replace with File…", () -> owner.replaceWithFile(node.path())));
                }
                items.add(action("Delete Entry", () -> owner.deleteEntry(node)));
                boolean modified = node.kind() == Kind.CLASS
                        ? owner.model().isUnitModified(node.unitId())
                        : owner.model().isModified(node.path());
                if (modified) {
                    items.add(new SeparatorMenuItem());
                    items.add(action("Discard Pending Changes", () -> owner.discardEntry(node)));
                }
            }
            return items.isEmpty() ? null : new ContextMenu(items.toArray(MenuItem[]::new));
        }

        private MenuItem action(String text, Runnable r) {
            MenuItem mi = new MenuItem(text);
            mi.setOnAction(e -> r.run());
            return mi;
        }
    }
}
