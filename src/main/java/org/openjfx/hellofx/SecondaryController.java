package org.openjfx.hellofx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class SecondaryController {

    @FXML private Label breadcrumbLabel;
    @FXML private VBox resultsContainer;
    @FXML private Button tabSuspected;
    @FXML private Button tabAnomaly;
    @FXML private Button tabInsights;
    @FXML private TextField chatField;

    private String conversationId = "";

    @FXML
    private void initialize() {
        breadcrumbLabel.setText(App.currentDate + "_" + App.currentFile);
        callChatApi("Hello World!!");
    }

    private void callChatApi(String query) {
        Thread thread = new Thread(() -> {
            try {
                Properties props = new Properties();
                try (InputStream in = getClass().getResourceAsStream("/application-dev.properties")) {
                    props.load(in);
                }

                String baseUrl = props.getProperty("api.base-url");
                String chatPath = props.getProperty("api.chat.path");
                String token = props.getProperty("api.auth.token");
                String user = props.getProperty("api.user");
                String responseMode = props.getProperty("api.chat.response-mode");

                ObjectMapper mapper = new ObjectMapper();
                ObjectNode bodyNode = mapper.createObjectNode();
                bodyNode.putObject("inputs");
                bodyNode.put("query", query);
                bodyNode.put("response_mode", responseMode);
                bodyNode.put("user", user);
                if (!conversationId.isEmpty()) {
                    bodyNode.put("conversation_id", conversationId);
                }
                String body = mapper.writeValueAsString(bodyNode);

                HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + chatPath).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
                String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                System.out.println("Status: " + status);
                System.out.println(response);

                var tree = mapper.readTree(response);
                String answer = tree.path("answer").asText("(응답 없음)");
                String newConvId = tree.path("conversation_id").asText("");
                if (!newConvId.isEmpty()) conversationId = newConvId;

                Platform.runLater(() -> {
                    addBubble(query, true);
                    addBubble(answer, false);
                });

            } catch (Exception e) {
                System.err.println("연결 오류: " + e.getMessage());
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void addBubble(String text, boolean isUser) {
        Label bubble = new Label(text);
        bubble.getStyleClass().add(isUser ? "bubble-right" : "bubble-left");
        HBox row = new HBox(bubble);
        row.getStyleClass().add(isUser ? "bubble-row-right" : "bubble-row-left");
        resultsContainer.getChildren().add(row);
    }

    private void showApiResult(int status, String response) {
        resultsContainer.getChildren().clear();
        VBox card = new VBox();
        card.getStyleClass().add("result-card");
        Label statusLabel = new Label("HTTP " + status);
        statusLabel.getStyleClass().add("history-date");
        Label text = new Label(response);
        text.getStyleClass().add("result-card-text");
        text.setWrapText(true);
        card.getChildren().addAll(statusLabel, text);
        resultsContainer.getChildren().add(card);
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
            chatField.clear();
            callChatApi(query);
        }
    }

    @FXML
    private void switchToPrimary() throws IOException {
        App.setRoot("primary");
    }
}
