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

    // FXML이 로드된 직후 자동 호출 — 브레드크럼 설정 후 첫 API 호출
    @FXML
    private void initialize() {
        breadcrumbLabel.setText(App.currentDate + "_" + App.currentFile);
        callChatApi("Hello World!!");
    }

    // initialize() 또는 handleChatSearch()에서 호출
    // query를 Dify chat-messages API로 POST, 응답의 answer/conversation_id 파싱
    // conversation_id를 저장해 이후 호출에서 대화 컨텍스트 유지
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

    // callChatApi()에서 응답 수신 후 JavaFX 스레드에서 호출
    // isUser=true → 오른쪽 파란 말풍선 (내 질문), false → 왼쪽 회색 말풍선 (AI 답변)
    private void addBubble(String text, boolean isUser) {
        Label bubble = new Label(text);
        bubble.getStyleClass().add(isUser ? "bubble-right" : "bubble-left");
        HBox row = new HBox(bubble);
        row.getStyleClass().add(isUser ? "bubble-row-right" : "bubble-row-left");
        resultsContainer.getChildren().add(row);
    }
    
    // setActiveTab()에서 호출 — 선택된 탭만 active 스타일 적용
    private void setActiveTab(Button active) {
        for (Button btn : new Button[]{tabSuspected, tabAnomaly, tabInsights}) {
            btn.getStyleClass().setAll("tab-inactive");
        }
        active.getStyleClass().setAll("tab-active");
    }

    // FXML: Suspected Logs 탭 클릭 시 호출
    @FXML
    private void handleTabSuspected() {
        setActiveTab(tabSuspected);
    }

    // FXML: Anomaly Detect 탭 클릭 시 호출
    @FXML
    private void handleTabAnomaly() {
        setActiveTab(tabAnomaly);
    }

    // FXML: Log Insights 탭 클릭 시 호출
    @FXML
    private void handleTabInsights() {
        setActiveTab(tabInsights);
    }

    // FXML: Search 버튼 클릭 또는 채팅 입력창에서 Enter 시 호출
    @FXML
    private void handleChatSearch() {
        String query = chatField.getText().trim();
        if (!query.isEmpty()) {
            chatField.clear();
            callChatApi(query);
        }
    }

    // FXML: ← Menu 버튼 클릭 시 호출 — Primary 화면으로 복귀
    @FXML
    private void switchToPrimary() throws IOException {
        App.setRoot("primary");
    }
}
