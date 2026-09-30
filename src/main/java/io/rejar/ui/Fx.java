package io.rejar.ui;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

/** JavaFX threading and dialog helpers. */
public final class Fx {

    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "rejar-worker-" + THREAD_ID.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private static String monospace;

    private Fx() {
    }

    /** First installed monospace family among the preferred ones (CSS font-family lists are not supported by JavaFX). */
    public static synchronized String monospaceFamily() {
        if (monospace == null) {
            java.util.List<String> installed = javafx.scene.text.Font.getFamilies();
            monospace = java.util.stream.Stream.of("JetBrains Mono", "Cascadia Mono", "Cascadia Code", "Consolas",
                            "Menlo", "SF Mono", "DejaVu Sans Mono", "Liberation Mono", "Ubuntu Mono", "Courier New")
                    .filter(installed::contains)
                    .findFirst()
                    .orElse("Monospaced");
        }
        return monospace;
    }

    /** Runs work in the background; callbacks are invoked on the FX thread. */
    public static <T> CompletableFuture<T> run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, EXECUTOR);
        future.whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                if (onError != null) {
                    onError.accept(unwrap(error));
                }
            } else if (onSuccess != null) {
                onSuccess.accept(result);
            }
        }));
        return future;
    }

    public static void background(Runnable work) {
        EXECUTOR.execute(work);
    }

    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static void runLater(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    public static Window window(Node node) {
        return node == null || node.getScene() == null ? null : node.getScene().getWindow();
    }

    public static void error(Window owner, String title, Throwable t) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle("ReJar");
        alert.setHeaderText(title);
        alert.setContentText(t.getMessage() == null ? t.toString() : t.getMessage());
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        TextArea details = new TextArea(sw.toString());
        details.setEditable(false);
        details.setWrapText(false);
        details.setPrefRowCount(14);
        GridPane.setVgrow(details, Priority.ALWAYS);
        GridPane.setHgrow(details, Priority.ALWAYS);
        GridPane content = new GridPane();
        content.add(new Label("Details:"), 0, 0);
        content.add(details, 0, 1);
        alert.getDialogPane().setExpandableContent(content);
        alert.showAndWait();
    }

    public static void info(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("ReJar");
        alert.setHeaderText(header);
        alert.getDialogPane().setMinWidth(480);
        alert.showAndWait();
    }

    public static void warn(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("ReJar");
        alert.setHeaderText(header);
        alert.getDialogPane().setMinWidth(480);
        alert.showAndWait();
    }

    public static boolean confirm(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setTitle("ReJar");
        alert.setHeaderText(header);
        alert.getDialogPane().setMinWidth(480);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }
}
