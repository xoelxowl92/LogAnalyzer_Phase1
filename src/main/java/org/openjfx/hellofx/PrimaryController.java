package org.openjfx.hellofx;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.stage.FileChooser;

public class PrimaryController {

    @FXML
    private ListView<String> fileListView;

    @FXML
    private void initialize() {
        fileListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        fileListView.setPlaceholder(new javafx.scene.control.Label("No files added"));
    }

    @FXML
    private void handleAddFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Add Files");
        List<File> files = fileChooser.showOpenMultipleDialog(fileListView.getScene().getWindow());
        if (files != null) {
            ObservableList<String> items = fileListView.getItems();
            for (File f : files) {
                String path = f.getAbsolutePath();
                if (!items.contains(path)) {
                    items.add(path);
                }
            }
        }
    }

    @FXML
    private void handleDeleteFile() {
        List<String> toRemove = new ArrayList<>(fileListView.getSelectionModel().getSelectedItems());
        if (!toRemove.isEmpty()) {
            fileListView.getItems().removeAll(toRemove);
        }
    }

    @FXML
    private void handleAnalyze() {
        ObservableList<String> items = fileListView.getItems();
        if (items.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle("Analyze");
            a.setHeaderText(null);
            a.setContentText("No files to analyze.");
            a.showAndWait();
            return;
        }

        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Analyze");
        a.setHeaderText(null);
        a.setContentText("Analyzing " + items.size() + " files...");
        a.showAndWait();

        // Temporary: print file list to console
        items.forEach(System.out::println);
    }

    @FXML
    private void handleViewResults() throws IOException {
        // Switch to the results view (secondary.fxml)
        App.setRoot("secondary");
    }

    @FXML
    private void switchToSecondary() throws IOException {
        App.setRoot("secondary");
    }
}
