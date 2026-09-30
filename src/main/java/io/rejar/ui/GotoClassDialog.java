package io.rejar.ui;

import io.rejar.core.ClassUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Quick "go to class" chooser filtering by simple name, qualified name or camel-case initials. */
final class GotoClassDialog extends Dialog<ClassUnit> {

    private static final int MAX_SHOWN = 500;

    GotoClassDialog(Window owner, Collection<ClassUnit> units, String initial) {
        initOwner(owner);
        setTitle("Go to class");
        setHeaderText("Type a class name (camel-case initials work: 'SBA' finds SpringBootApplication)");
        TextField filter = new TextField(initial == null ? "" : initial);
        ListView<ClassUnit> list = new ListView<>();
        list.setPrefSize(640, 420);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ClassUnit unit, boolean empty) {
                super.updateItem(unit, empty);
                setText(empty || unit == null ? null
                        : unit.simpleName() + "   — " + unit.packageName() + (unit.prefix().isEmpty() ? "" : "  (" + unit.prefix() + ")"));
            }
        });
        List<ClassUnit> all = new ArrayList<>(units);
        all.sort(Comparator.comparing(ClassUnit::simpleName, String.CASE_INSENSITIVE_ORDER));
        Runnable update = () -> {
            String f = filter.getText().trim();
            List<ClassUnit> shown = new ArrayList<>();
            for (ClassUnit u : all) {
                if (matches(u, f)) {
                    shown.add(u);
                    if (shown.size() >= MAX_SHOWN) {
                        break;
                    }
                }
            }
            String lower = f.toLowerCase(Locale.ROOT);
            shown.sort(Comparator.comparing((ClassUnit u) -> !u.simpleName().toLowerCase(Locale.ROOT).startsWith(lower))
                    .thenComparing(ClassUnit::simpleName, String.CASE_INSENSITIVE_ORDER));
            list.getItems().setAll(shown);
            list.getSelectionModel().selectFirst();
        };
        filter.textProperty().addListener((o, a, b) -> update.run());
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                list.getSelectionModel().selectNext();
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                list.getSelectionModel().selectPrevious();
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                e.consume();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                setResult(list.getSelectionModel().getSelectedItem());
                close();
            }
        });
        update.run();
        VBox box = new VBox(8, filter, list);
        box.setPadding(new Insets(8));
        getDialogPane().setContent(box);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(b -> b == ButtonType.OK ? list.getSelectionModel().getSelectedItem() : null);
        Platform.runLater(filter::requestFocus);
    }

    private static boolean matches(ClassUnit unit, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        String name = unit.simpleName();
        String lower = filter.toLowerCase(Locale.ROOT);
        if (name.toLowerCase(Locale.ROOT).contains(lower) || unit.fqcn().toLowerCase(Locale.ROOT).contains(lower)) {
            return true;
        }
        // camel case: every filter char must start a "hump" in order
        int pos = 0;
        for (char c : filter.toCharArray()) {
            if (!Character.isUpperCase(c)) {
                return false;
            }
            int found = -1;
            for (int i = pos; i < name.length(); i++) {
                if (name.charAt(i) == c && (i == 0 || Character.isUpperCase(name.charAt(i)))) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            pos = found + 1;
        }
        return true;
    }
}
