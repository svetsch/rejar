package io.rejar.ui;

import io.rejar.core.ClassUnit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Manual UI smoke run (not a unit test): opens a jar, decompiles a class, edits/compiles it, searches, and writes
 * PNG snapshots. Usage: UiSmoke &lt;jar&gt; &lt;fqcn to open&gt; &lt;output dir&gt; [dark]
 */
public final class UiSmoke {

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        String fqcn = args[1];
        File out = new File(args[2]);
        out.mkdirs();
        if (args.length > 3) {
            Settings.setDarkTheme("dark".equals(args[3]));
        }
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        MainWindow[] holder = new MainWindow[1];
        onFx(() -> {
            Stage stage = new Stage();
            holder[0] = new MainWindow(stage, null);
            holder[0].show();
            stage.setWidth(1500);
            stage.setHeight(950);
            holder[0].openJar(jar);
        });
        MainWindow window = holder[0];
        waitFor(() -> window.selectedJarTab() != null, 20);
        JarTab tab = window.selectedJarTab();
        snapshot(window, new File(out, "1-opened.png"));

        ClassEditorTab[] editor = new ClassEditorTab[1];
        onFx(() -> {
            ClassUnit unit = tab.model().units().values().stream().filter(u -> u.fqcn().equals(fqcn)).findFirst().orElseThrow();
            editor[0] = tab.openUnit(unit);
        });
        waitFor(() -> !editor[0].sourceText().startsWith("// Decompiling"), 60);
        Thread.sleep(800);
        snapshot(window, new File(out, "2-decompiled.png"));

        // edit + compile
        onFx(() -> editor[0].startEditing());
        Thread.sleep(300);
        onFx(() -> {
            String src = editor[0].sourceText();
            int idx = src.lastIndexOf('}');
            String edited = src.substring(0, idx) + "\n    public static String rejarPatched() {\n        return \"patched by ReJar\";\n    }\n}\n";
            ((CodeEditor) editor[0].getContent().lookup(".code-editor")).setText(edited);
        });
        Thread.sleep(300);
        onFx(() -> tab.compileAndApply(editor[0]));
        waitFor(() -> tab.model().hasPendingChanges() || editor[0].getContent().lookup(".problems").isVisible(), 180);
        Thread.sleep(1500);
        snapshot(window, new File(out, "3-compiled.png"));
        System.out.println("pending changes: " + tab.model().pendingList().size());

        // search
        onFx(tab::showSearch);
        Thread.sleep(300);
        onFx(() -> {
            SearchPane pane = (SearchPane) tab.getContent().lookup(".search-pane");
            pane.setQuery("toString");
            ((javafx.scene.control.Button) pane.lookupAll(".button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button b && b.getText().equals("Search"))
                    .findFirst().orElseThrow()).fire();
        });
        Thread.sleep(8000);
        snapshot(window, new File(out, "4-search.png"));

        onFx(tab::showChanges);
        Thread.sleep(500);
        snapshot(window, new File(out, "5-changes.png"));
        System.out.println("maven classpath: " + tab.classpathConfig().mavenClasspath);

        // write a new jar and open it: the history must show the change set
        Path patched = out.toPath().resolve("patched-" + System.currentTimeMillis() + ".jar");
        io.rejar.core.JarWriter.write(tab.model(), patched, "Add rejarPatched() (smoke test)");
        onFx(() -> window.openJar(patched));
        waitFor(() -> window.selectedJarTab() != null && window.selectedJarTab() != tab, 20);
        JarTab patchedTab = window.selectedJarTab();
        onFx(() -> {
            patchedTab.showChanges();
            patchedTab.openUnit(patchedTab.model().units().values().stream()
                    .filter(u -> u.fqcn().equals(fqcn)).findFirst().orElseThrow());
        });
        Thread.sleep(3000);
        snapshot(window, new File(out, "6-history.png"));
        System.out.println("history: " + patchedTab.model().history().changeSets.size() + " change set(s)");

        onFx(() -> {
            for (Tab t : window.tabPane().getTabs()) {
                ((JarTab) t).model().discardAll();
            }
            Platform.exit();
        });
        System.exit(0);
    }

    private static void onFx(Runnable r) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                latch.countDown();
            }
        });
        latch.await(30, TimeUnit.SECONDS);
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void waitFor(Check check, int seconds) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            boolean[] ok = new boolean[1];
            onFx(() -> {
                try {
                    ok[0] = check.ok();
                } catch (Exception e) {
                    ok[0] = false;
                }
            });
            if (ok[0]) {
                return;
            }
            Thread.sleep(200);
        }
        System.err.println("timeout waiting for condition");
    }

    private static void snapshot(MainWindow window, File file) throws Exception {
        WritableImage[] image = new WritableImage[1];
        onFx(() -> image[0] = window.stage().getScene().snapshot(null));
        int w = (int) image[0].getWidth();
        int h = (int) image[0].getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image[0].getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", file);
        System.out.println("snapshot " + file);
    }
}
