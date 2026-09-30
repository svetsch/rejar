package io.rejar.ui;

import io.rejar.core.ClassNames;
import io.rejar.core.SearchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.PatternSyntaxException;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

/** Search in decompiled classes, resources and entry names of a jar. */
final class SearchPane extends VBox {

    private static final int MAX_HITS = 20_000;

    private final JarTab owner;
    private final TextField query = new TextField();
    private final CheckBox matchCase = new CheckBox("Match case");
    private final CheckBox wholeWord = new CheckBox("Words");
    private final CheckBox regex = new CheckBox("Regex");
    private final CheckBox inClasses = new CheckBox("Decompiled classes");
    private final CheckBox inResources = new CheckBox("Resources");
    private final CheckBox inNames = new CheckBox("Entry names");
    private final Button searchButton = new Button("Search");
    private final Button cancelButton = new Button("Cancel");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = new Label();
    private final TreeView<Object> results = new TreeView<>(new TreeItem<>("results"));
    private final Map<String, TreeItem<Object>> fileItems = new HashMap<>();
    private final ConcurrentLinkedQueue<SearchService.Hit> queue = new ConcurrentLinkedQueue<>();
    private final Timeline flusher = new Timeline(new KeyFrame(Duration.millis(150), e -> flush()));

    private AtomicBoolean cancel = new AtomicBoolean();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger total = new AtomicInteger();
    private volatile String current = "";
    private volatile boolean running;
    private int hitCount;
    private long startTime;

    private record FileNode(SearchService.Kind kind, String path, String label) {
    }

    SearchPane(JarTab owner) {
        this.owner = owner;
        getStyleClass().add("search-pane");
        setSpacing(4);
        setPadding(new Insets(6));

        query.setPromptText("Text to search in the jar (decompiles classes on the fly)");
        query.setOnAction(e -> start());
        query.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && running) {
                cancel.set(true);
            }
        });
        HBox.setHgrow(query, Priority.ALWAYS);
        searchButton.setDefaultButton(false);
        searchButton.getStyleClass().add("accent");
        searchButton.setOnAction(e -> start());
        cancelButton.setOnAction(e -> cancel.set(true));
        cancelButton.setDisable(true);
        HBox row1 = new HBox(6, query, searchButton, cancelButton);
        row1.setAlignment(Pos.CENTER_LEFT);

        inClasses.setSelected(true);
        inResources.setSelected(true);
        inNames.setSelected(true);
        progress.setPrefWidth(180);
        progress.setVisible(false);
        HBox row2 = new HBox(10, matchCase, wholeWord, regex, new Separator(javafx.geometry.Orientation.VERTICAL),
                new Label("Search in:"), inClasses, inResources, inNames, new Separator(javafx.geometry.Orientation.VERTICAL),
                progress, status);
        row2.setAlignment(Pos.CENTER_LEFT);

        results.setShowRoot(false);
        results.getRoot().setExpanded(true);
        results.setCellFactory(tv -> new ResultCell());
        results.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        results.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
            }
        });
        VBox.setVgrow(results, Priority.ALWAYS);
        getChildren().addAll(row1, row2, results);
        flusher.setCycleCount(Timeline.INDEFINITE);
    }

    void focus() {
        query.requestFocus();
        query.selectAll();
    }

    void setQuery(String text) {
        query.setText(text);
    }

    void cancel() {
        cancel.set(true);
    }

    private void start() {
        String text = query.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        if (running) {
            cancel.set(true);
        }
        SearchService.Query q = new SearchService.Query(text, regex.isSelected(), matchCase.isSelected(),
                wholeWord.isSelected(), inClasses.isSelected(), inResources.isSelected(), inNames.isSelected());
        try {
            q.compile();
        } catch (PatternSyntaxException e) {
            status.setText("Invalid regex: " + e.getDescription());
            return;
        }
        results.getRoot().getChildren().clear();
        fileItems.clear();
        queue.clear();
        hitCount = 0;
        done.set(0);
        total.set(0);
        AtomicBoolean myCancel = new AtomicBoolean();
        cancel = myCancel;
        running = true;
        startTime = System.currentTimeMillis();
        searchButton.setDisable(true);
        cancelButton.setDisable(false);
        progress.setVisible(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        flusher.play();
        Fx.run(() -> {
            SearchService.search(owner.model(), owner.decompiler(), q, hit -> {
                queue.add(hit);
            }, (d, t, c) -> {
                done.set(d);
                total.set(t);
                current = c;
            }, myCancel);
            return null;
        }, ignored -> finished(myCancel), error -> {
            finished(myCancel);
            status.setText("Search failed: " + error.getMessage());
        });
    }

    private void finished(AtomicBoolean myCancel) {
        if (myCancel != cancel) {
            return; // a newer search is running
        }
        running = false;
        flush();
        flusher.stop();
        searchButton.setDisable(false);
        cancelButton.setDisable(true);
        progress.setVisible(false);
        long seconds = (System.currentTimeMillis() - startTime) / 1000;
        status.setText((myCancel.get() ? "Cancelled: " : "") + hitCount + " match(es) in " + fileItems.size()
                + " file(s)" + (hitCount >= MAX_HITS ? " (limit reached)" : "") + " – " + seconds + " s");
        owner.main().setStatus("Search finished: " + hitCount + " match(es)");
    }

    private void flush() {
        SearchService.Hit hit;
        int budget = 2000;
        while (budget-- > 0 && (hit = queue.poll()) != null) {
            if (hitCount >= MAX_HITS) {
                cancel.set(true);
                queue.clear();
                break;
            }
            hitCount++;
            String key = hit.kind() + ":" + hit.path();
            TreeItem<Object> fileItem = fileItems.get(key);
            if (fileItem == null) {
                String label = hit.kind() == SearchService.Kind.CLASS
                        ? ClassNames.toFqcn(ClassNames.internalName(hit.path() + ClassNames.CLASS_SUFFIX))
                        : hit.path();
                fileItem = new TreeItem<>(new FileNode(hit.kind(), hit.path(), label));
                fileItem.setExpanded(fileItems.size() < 50);
                fileItems.put(key, fileItem);
                results.getRoot().getChildren().add(fileItem);
            }
            if (hit.kind() != SearchService.Kind.NAME) {
                fileItem.getChildren().add(new TreeItem<>(hit));
            }
        }
        if (running) {
            int t = total.get();
            if (t > 0) {
                progress.setProgress((double) done.get() / t);
            }
            status.setText(done.get() + "/" + t + " · " + hitCount + " match(es) · " + current);
        }
    }

    private void openSelected() {
        TreeItem<Object> item = results.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        if (item.getValue() instanceof SearchService.Hit hit) {
            owner.openHit(hit);
        } else if (item.getValue() instanceof FileNode node) {
            if (node.kind() == SearchService.Kind.CLASS) {
                owner.openUnitById(node.path());
            } else {
                owner.openEntry(node.path());
            }
        }
    }

    private static final class ResultCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(null);
            if (empty || item == null) {
                return;
            }
            if (item instanceof FileNode node) {
                Text name = new Text(node.label());
                name.getStyleClass().add("search-file");
                String suffix = switch (node.kind()) {
                    case CLASS -> "  class";
                    case RESOURCE -> "  resource";
                    case NAME -> "  entry name";
                };
                Text kind = new Text(suffix + (getTreeItem().getChildren().isEmpty() ? "" : " (" + getTreeItem().getChildren().size() + ")"));
                kind.getStyleClass().add("search-kind");
                setGraphic(new TextFlow(name, kind));
            } else if (item instanceof SearchService.Hit hit) {
                String line = hit.lineText();
                int start = Math.min(hit.column(), line.length());
                int end = Math.min(start + hit.length(), line.length());
                String prefix = line.substring(0, start);
                // keep long lines readable: trim leading whitespace and far context
                String trimmed = prefix.stripLeading();
                if (trimmed.length() > 80) {
                    trimmed = "…" + trimmed.substring(trimmed.length() - 80);
                }
                Text lineNo = new Text(hit.line() + ": ");
                lineNo.getStyleClass().add("search-line");
                Text before = new Text(trimmed);
                Text match = new Text(line.substring(start, end));
                match.getStyleClass().add("search-match");
                String rest = line.substring(end);
                Text after = new Text(rest.length() > 160 ? rest.substring(0, 160) + "…" : rest);
                before.getStyleClass().add("search-text");
                after.getStyleClass().add("search-text");
                TextFlow flow = new TextFlow(lineNo, before, match, after);
                flow.setStyle("-fx-font-family: \"" + Fx.monospaceFamily() + "\";");
                setGraphic(flow);
            }
        }
    }
}
