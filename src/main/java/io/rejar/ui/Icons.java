package io.rejar.ui;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/** Application icon, rendered at several sizes (see resources io/rejar/ui/icons). */
public final class Icons {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256, 512};
    private static List<Image> all;

    private Icons() {
    }

    /** All sizes, so the platform picks the best one for title bars, task bars and docks. */
    public static synchronized List<Image> all() {
        if (all == null) {
            List<Image> images = new ArrayList<>();
            for (int size : SIZES) {
                Image image = load(size);
                if (image != null) {
                    images.add(image);
                }
            }
            all = List.copyOf(images);
        }
        return all;
    }

    public static ImageView view(int size) {
        // smallest rendered size covering the display size (x2 for HiDPI screens)
        int wanted = size * 2;
        int best = SIZES[SIZES.length - 1];
        for (int s : SIZES) {
            if (s >= wanted) {
                best = s;
                break;
            }
        }
        Image image = load(best);
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setSmooth(true);
        return view;
    }

    private static Image load(int size) {
        try (InputStream in = Icons.class.getResourceAsStream("icons/rejar-" + size + ".png")) {
            return in == null ? null : new Image(in);
        } catch (java.io.IOException e) {
            return null;
        }
    }
}
