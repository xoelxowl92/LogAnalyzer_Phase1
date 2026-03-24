package org.openjfx.hellofx;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

public class PrimaryController {

    @FXML private HBox navAnalyze;
    @FXML private HBox navHistory;
    @FXML private VBox historyList;
    @FXML private TextField filePathField;
    @FXML private CheckBox checkSuspectedLogs;
    @FXML private CheckBox checkAnomalyDetect;
    @FXML private CheckBox checkLogInsights;

    @FXML
    private void initialize() {
        refreshHistoryList();
    }

    private void refreshHistoryList() {
        historyList.getChildren().clear();
        for (String[] entry : App.history) {
            VBox item = new VBox(2);
            item.getStyleClass().add("history-item");

            Label dateLabel = new Label(entry[0]);
            dateLabel.getStyleClass().add("history-date");

            Label fileLabel = new Label(entry[1]);
            fileLabel.getStyleClass().add("history-filename");
            fileLabel.setMaxWidth(130);
            fileLabel.setWrapText(false);
            fileLabel.setEllipsisString("...");

            item.getChildren().addAll(dateLabel, fileLabel);

            final String[] ref = entry;
            item.setOnMouseClicked(e -> openHistoryItem(ref));
            historyList.getChildren().add(item);
        }
    }

    private void openHistoryItem(String[] entry) {
        App.currentDate = entry[0];
        App.currentFile = entry[1];
        try {
            App.setRoot("secondary");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleNavAnalyze() {
        navAnalyze.getStyleClass().add("nav-item-selected");
        navHistory.getStyleClass().remove("nav-item-selected");
    }

    @FXML
    private void handleNavHistory() {
        navHistory.getStyleClass().add("nav-item-selected");
        navAnalyze.getStyleClass().remove("nav-item-selected");
    }

    @FXML
    private void handleFieldClick(MouseEvent e) {
        openFileChooser();
    }

    @FXML
    private void handleAnalyze() {
        // TODO: 파일 업로드 창 임시 주석처리 - API 테스트용
        // String path = filePathField.getText().trim();
        // if (path.isEmpty()) {
        //     path = openFileChooser();
        //     if (path == null) return;
        // }
        String path = "test_dummy.log"; // API 테스트용 더미 파일명

        String date = LocalDate.now().toString();
        String filename = new File(path).getName();

        App.history.add(0, new String[]{date, filename});
        App.currentFile = filename;
        App.currentDate = date;

        try {
            App.setRoot("secondary");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String openFileChooser() {
        FileChooser fc = new FileChooser();
        fc.setTitle("로그 파일 선택");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Log Files", "*.log", "*.txt", "*.dat"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = fc.showOpenDialog(filePathField.getScene().getWindow());
        if (file != null) {
            filePathField.setText(file.getAbsolutePath());
            return file.getAbsolutePath();
        }
        return null;
    }
}
