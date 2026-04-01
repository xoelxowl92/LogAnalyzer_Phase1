package org.openjfx.hellofx;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
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

    // FXML이 로드된 직후 자동 호출 — 사이드바 히스토리 목록 초기 렌더링
    @FXML
    private void initialize() {
        refreshHistoryList();
    }

    // initialize() 및 Secondary → Primary 복귀 시 호출
    // App.history를 순회하며 날짜/파일명 카드를 historyList에 렌더링
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

    // refreshHistoryList()에서 히스토리 아이템 클릭 시 호출
    // App에 날짜/파일명 세팅 후 Secondary 화면으로 이동
    private void openHistoryItem(String[] entry) {
        App.currentDate = entry[0];
        App.currentFile = entry[1];
        try {
            App.setRoot("secondary");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // FXML: 사이드바 Analyze 메뉴 클릭 시 호출 — 선택 스타일 토글
    @FXML
    private void handleNavAnalyze() {
        navAnalyze.getStyleClass().add("nav-item-selected");
        navHistory.getStyleClass().remove("nav-item-selected");
    }

    // FXML: 사이드바 History 메뉴 클릭 시 호출 — 선택 스타일 토글
    @FXML
    private void handleNavHistory() {
        navHistory.getStyleClass().add("nav-item-selected");
        navAnalyze.getStyleClass().remove("nav-item-selected");
    }

    // FXML: 파일 경로 입력 필드 클릭 시 호출 — 파일 선택 다이얼로그 오픈
    @FXML
    private void handleFieldClick(MouseEvent e) {
        openFileChooser();
    }

    // FXML: Analyze 버튼 클릭 시 호출
    // 체크박스 미선택 시 차단, 파일 경로 없으면 FileChooser 오픈, App에 세팅 후 Secondary 화면으로 이동
    @FXML
    private void handleAnalyze() {
        if (!checkSuspectedLogs.isSelected() && !checkAnomalyDetect.isSelected() && !checkLogInsights.isSelected()) {
            return;
        }

        String path = filePathField.getText().trim();
        if (path.isEmpty()) {
            path = openFileChooser();
            if (path == null) return;
        }

        String date = LocalDate.now().toString();
        String filename = Normalizer.normalize(new File(path).getName(), Normalizer.Form.NFC);

        App.history.add(0, new String[]{date, filename});
        App.currentFile = filename;
        App.currentDate = date;
        App.currentFilePath = path;
        App.runSuspected = checkSuspectedLogs.isSelected();
        App.runAnomaly   = checkAnomalyDetect.isSelected();
        App.runInsights  = checkLogInsights.isSelected();

        try {
            App.setRoot("secondary");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // handleFieldClick()에서 호출 — 파일 선택 다이얼로그 표시
    // 선택된 파일 경로를 filePathField에 세팅하고 반환, 취소 시 null 반환
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
