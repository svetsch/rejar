package io.rejar;

import io.rejar.ui.MainWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.stage.Stage;

/** JavaFX application: jar decompiler and patcher. */
public class RejarApp extends Application {

    private MainWindow window;

    public static void main(String[] args) {
        launch(RejarApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        window = new MainWindow(stage, getHostServices());
        window.show();
        for (String arg : getParameters().getRaw()) {
            Path path = Path.of(arg);
            if (Files.isRegularFile(path)) {
                window.openJar(path);
            }
        }
    }

    @Override
    public void stop() {
        if (window != null) {
            window.dispose();
        }
    }
}
