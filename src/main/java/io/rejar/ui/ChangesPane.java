package io.rejar.ui;

import io.rejar.core.ClassNames;
import io.rejar.core.History;
import io.rejar.core.JarModel;
import io.rejar.core.PendingChange;
import io.rejar.core.PendingSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Pending (unsaved) changes and the modification history recorded in the jar. */
final class ChangesPane extends SplitPane {

    private final JarTab owner;
    private final TableView<PendingChange> pending = new TableView<>();
    private final Label pendingTitle = new Label();
    private final TreeView<Object> history = new TreeView<>(new TreeItem<>("history"));
    private final Label historyTitle = new Label();
    private final Button discardSelected = new Button("Discard Selected");
    private final Button discardAll = new Button("Discard All");
    private final Button comparePending = new Button("Compare Source");
    private final Button save = new Button("Save as New JAR…");
    private final Button revert = new Button("Revert Selected…");
    private final Button compareHistory = new Button("Compare Source");
    private final Button extractCopy = new Button("Extract Stored Copy…");

    ChangesPane(JarTab owner) {
        this.owner = owner;
        getItems().addAll(buildPending(), buildHistory());
        setDividerPositions(0.5);
        refresh();
    }

    private BorderPane buildPending() {
        pendingTitle.getStyleClass().add("section-title");
        TableColumn<PendingChange, String> type = new TableColumn<>("Change");
        type.setCellValueFactory(c -> new ReadOnlyStringWrapper(changeType(c.getValue())));
        type.setPrefWidth(80);
        TableColumn<PendingChange, String> path = new TableColumn<>("Entry");
        path.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().path()));
        path.setPrefWidth(380);
        TableColumn<PendingChange, String> origin = new TableColumn<>("Origin");
        origin.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().reason()));
        origin.setPrefWidth(200);
        pending.getColumns().add(type);
        pending.getColumns().add(path);
        pending.getColumns().add(origin);
        pending.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        pending.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        pending.setPlaceholder(new Label("No pending change. Edit a class or a resource, then 'Compile & Apply'."));
        pending.setRowFactory(tv -> {
            TableRow<PendingChange> row = new TableRow<>() {
                @Override
                protected void updateItem(PendingChange item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("row-added", "row-deleted", "row-modified");
                    if (!empty && item != null) {
                        getStyleClass().add("row-" + changeType(item).toLowerCase());
                    }
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    PendingChange c = row.getItem();
                    if (c.unitId() != null) {
                        owner.openUnitById(c.unitId());
                    } else if (!c.isDelete()) {
                        owner.openEntry(c.path());
                    }
                }
            });
            return row;
        });
        pending.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> updateButtons());

        discardSelected.setOnAction(e -> {
            List<String> paths = pending.getSelectionModel().getSelectedItems().stream().map(PendingChange::path).toList();
            if (!paths.isEmpty() && Fx.confirm(Fx.window(this), "Discard changes",
                    "Discard the pending change of " + paths.size() + " entr" + (paths.size() == 1 ? "y" : "ies") + "?")) {
                owner.model().discard(paths);
            }
        });
        discardAll.setOnAction(e -> {
            if (Fx.confirm(Fx.window(this), "Discard all changes", "Discard every pending change of this jar?")) {
                owner.model().discardAll();
            }
        });
        comparePending.setOnAction(e -> {
            PendingChange c = pending.getSelectionModel().getSelectedItem();
            PendingSource ps = c == null || c.unitId() == null ? null : owner.model().pendingSource(c.unitId());
            if (ps != null) {
                owner.openCompare(ClassNames.simpleName(ps.className()), ps.originalSource(), ps.source(),
                        "Decompiled (original)", "Edited");
            }
        });
        save.getStyleClass().add("accent");
        save.setOnAction(e -> owner.saveAsNewJar());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(6, discardSelected, discardAll, comparePending, spacer, save);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(4, 0, 0, 0));
        VBox top = new VBox(pendingTitle);
        top.setPadding(new Insets(0, 0, 4, 0));
        BorderPane pane = new BorderPane(pending, top, null, buttons, null);
        pane.setPadding(new Insets(6));
        return pane;
    }

    private BorderPane buildHistory() {
        historyTitle.getStyleClass().add("section-title");
        history.setShowRoot(false);
        history.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        history.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("history-set");
                if (empty || item == null) {
                    setText(null);
                } else if (item instanceof History.ChangeSet cs) {
                    getStyleClass().add("history-set");
                    String when = cs.timestamp == null ? "" : cs.timestamp.replace('T', ' ');
                    if (when.length() > 19) {
                        when = when.substring(0, 19);
                    }
                    setText(cs.id + "  ·  " + when + "  ·  " + cs.user
                            + (cs.description == null || cs.description.isBlank() ? "" : "  ·  " + cs.description)
                            + (cs.reverts.isEmpty() ? "" : "  [reverts " + String.join(", ", cs.reverts) + "]"));
                } else if (item instanceof History.SourceChange sc) {
                    setText("source  " + sc.className + "  (compiled for Java " + sc.release + ")");
                } else if (item instanceof History.ChangeEntry ce) {
                    setText(ce.type + "  " + ce.path + (ce.revertOf != null ? "  (revert of " + ce.revertOf + ")" : ""));
                } else {
                    setText(item.toString());
                }
            }
        });
        history.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> updateButtons());

        revert.setTooltip(new javafx.scene.control.Tooltip(
                "Stage the restoration of the original bytes of the selected change set / entries.\n"
                        + "The result is written with 'Save as New JAR' (and recorded as a new change set)."));
        revert.setOnAction(e -> revertSelected());
        compareHistory.setOnAction(e -> compareHistorySource());
        extractCopy.setOnAction(e -> extractStoredCopy());

        HBox buttons = new HBox(6, revert, compareHistory, extractCopy);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(4, 0, 0, 0));
        VBox top = new VBox(historyTitle);
        top.setPadding(new Insets(0, 0, 4, 0));
        BorderPane pane = new BorderPane(history, top, null, buttons, null);
        pane.setPadding(new Insets(6));
        return pane;
    }

    void refresh() {
        JarModel model = owner.model();
        List<PendingChange> changes = model.pendingList();
        pending.getItems().setAll(changes);
        pendingTitle.setText("Pending changes (not saved): " + changes.size());

        TreeItem<Object> root = history.getRoot();
        root.getChildren().clear();
        List<History.ChangeSet> sets = model.history().changeSets;
        for (int i = sets.size() - 1; i >= 0; i--) {
            History.ChangeSet cs = sets.get(i);
            TreeItem<Object> csItem = new TreeItem<>(cs);
            for (History.SourceChange sc : cs.sources) {
                csItem.getChildren().add(new TreeItem<>(sc));
            }
            for (History.ChangeEntry ce : cs.entries) {
                csItem.getChildren().add(new TreeItem<>(ce));
            }
            csItem.setExpanded(i == sets.size() - 1);
            root.getChildren().add(csItem);
        }
        historyTitle.setText(sets.isEmpty()
                ? "History: no change recorded in this jar (recorded when saving a new jar)"
                : "History recorded in this jar (META-INF/rejar): " + sets.size() + " change set(s)");
        updateButtons();
    }

    private void updateButtons() {
        boolean hasPending = !pending.getItems().isEmpty();
        discardAll.setDisable(!hasPending);
        discardSelected.setDisable(pending.getSelectionModel().isEmpty());
        save.setDisable(!hasPending);
        PendingChange c = pending.getSelectionModel().getSelectedItem();
        comparePending.setDisable(c == null || c.unitId() == null || owner.model().pendingSource(c.unitId()) == null);

        TreeItem<Object> sel = history.getSelectionModel().getSelectedItem();
        Object v = sel == null ? null : sel.getValue();
        revert.setDisable(!(v instanceof History.ChangeSet || v instanceof History.ChangeEntry));
        compareHistory.setDisable(!(v instanceof History.SourceChange
                || (v instanceof History.ChangeSet cs && !cs.sources.isEmpty())));
        extractCopy.setDisable(!(v instanceof History.ChangeEntry));
    }

    private static History.ChangeSet changeSetOf(TreeItem<Object> item) {
        if (item.getValue() instanceof History.ChangeSet cs) {
            return cs;
        }
        TreeItem<Object> parent = item.getParent();
        return parent != null && parent.getValue() instanceof History.ChangeSet cs ? cs : null;
    }

    private void revertSelected() {
        Map<History.ChangeSet, List<History.ChangeEntry>> selection = new LinkedHashMap<>();
        for (TreeItem<Object> item : history.getSelectionModel().getSelectedItems()) {
            if (item == null) {
                continue;
            }
            History.ChangeSet cs = changeSetOf(item);
            if (cs == null) {
                continue;
            }
            List<History.ChangeEntry> list = selection.computeIfAbsent(cs, k -> new ArrayList<>());
            if (item.getValue() instanceof History.ChangeSet) {
                for (History.ChangeEntry e : cs.entries) {
                    if (!list.contains(e)) {
                        list.add(e);
                    }
                }
            } else if (item.getValue() instanceof History.ChangeEntry e && !list.contains(e)) {
                list.add(e);
            }
        }
        selection.values().removeIf(List::isEmpty);
        if (!selection.isEmpty()) {
            owner.revert(selection);
        }
    }

    private void compareHistorySource() {
        TreeItem<Object> item = history.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        History.SourceChange sc = item.getValue() instanceof History.SourceChange s ? s
                : item.getValue() instanceof History.ChangeSet cs && !cs.sources.isEmpty() ? cs.sources.get(0) : null;
        History.ChangeSet cs = changeSetOf(item);
        if (sc == null || cs == null) {
            return;
        }
        try {
            byte[] original = sc.originalSource == null ? null : owner.model().read(sc.originalSource);
            byte[] modified = owner.model().read(sc.modifiedSource);
            owner.openCompare(ClassNames.simpleName(sc.className.replace('.', '/')) + " @" + cs.id,
                    original == null ? "// not stored" : new String(original, StandardCharsets.UTF_8),
                    modified == null ? "// not stored" : new String(modified, StandardCharsets.UTF_8),
                    "Decompiled before " + cs.id, "Edited in " + cs.id);
        } catch (Exception ex) {
            Fx.error(Fx.window(this), "Cannot read stored sources", ex);
        }
    }

    private void extractStoredCopy() {
        TreeItem<Object> item = history.getSelectionModel().getSelectedItem();
        if (item != null && item.getValue() instanceof History.ChangeEntry ce) {
            List<String> choices = new ArrayList<>();
            if (ce.originalCopy != null) {
                choices.add("Original (before the change)");
            }
            if (ce.modifiedCopy != null) {
                choices.add("Modified (after the change)");
            }
            javafx.scene.control.ChoiceDialog<String> dialog = new javafx.scene.control.ChoiceDialog<>(choices.get(0), choices);
            dialog.initOwner(Fx.window(this));
            dialog.setTitle("Extract stored copy");
            dialog.setHeaderText(ce.path);
            dialog.showAndWait().ifPresent(choice -> {
                String stored = choice.startsWith("Original") ? ce.originalCopy : ce.modifiedCopy;
                owner.extractAs(stored, ce.path.substring(ce.path.lastIndexOf('/') + 1));
            });
        }
    }

    private String changeType(PendingChange c) {
        if (c.isDelete()) {
            return "DELETED";
        }
        return owner.model().existsInOriginal(c.path()) ? "MODIFIED" : "ADDED";
    }
}
