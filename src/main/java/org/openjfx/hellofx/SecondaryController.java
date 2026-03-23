package org.openjfx.hellofx;

import java.io.IOException;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class SecondaryController {

    @FXML private Label breadcrumbLabel;
    @FXML private VBox resultsContainer;
    @FXML private Button tabSuspected;
    @FXML private Button tabAnomaly;
    @FXML private Button tabInsights;
    @FXML private TextField chatField;

    @FXML
    private void initialize() {
        breadcrumbLabel.setText(App.currentDate + "_" + App.currentFile);
        loadTab("suspected");
    }

    private void loadTab(String tab) {
        resultsContainer.getChildren().clear();
        // 분석 로직 구현 전 플레이스홀더 카드 3개
        for (int i = 0; i < 3; i++) {
            VBox card = new VBox();
            card.getStyleClass().add("result-card");
            Label text = new Label("분석 결과가 여기에 표시됩니다. [" + tab + " #" + (i + 1) + "]");
            text.getStyleClass().add("result-card-text");
            text.setWrapText(true);
            card.getChildren().add(text);
            resultsContainer.getChildren().add(card);
        }
    }

    private void setActiveTab(Button active) {
        for (Button btn : new Button[]{tabSuspected, tabAnomaly, tabInsights}) {
            btn.getStyleClass().setAll("tab-inactive");
        }
        active.getStyleClass().setAll("tab-active");
    }

    @FXML
    private void handleTabSuspected() {
        setActiveTab(tabSuspected);
        loadTab("suspected");
    }

    @FXML
    private void handleTabAnomaly() {
        setActiveTab(tabAnomaly);
        loadTab("anomaly");
    }

    @FXML
    private void handleTabInsights() {
        setActiveTab(tabInsights);
        loadTab("insights");
    }

    @FXML
    private void handleChatSearch() {
        String query = chatField.getText().trim();
        if (!query.isEmpty()) {
            // 향후 AI 연동 시 구현
            chatField.clear();
        }
    }

    @FXML
    private void switchToPrimary() throws IOException {
        App.setRoot("primary");
    }
}
