package org.openjfx.hellofx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private String uploadFileId = "";

    // FXML이 로드된 직후 자동 호출 — 브레드크럼 설정 후 파일 업로드
    @FXML
    private void initialize() {
        breadcrumbLabel.setText(App.currentDate + "_" + App.currentFile);
        uploadFile();
    }

    // initialize()에서 호출 — 선택된 파일을 Dify /v1/files/upload로 multipart/form-data 전송
    // response를 콘솔과 채팅창에 출력
    private void uploadFile() {
        Thread thread = new Thread(() -> {
            try {
                Properties props = new Properties();
                try (InputStream in = getClass().getResourceAsStream("/application-dev.properties")) {
                    props.load(in);
                }

                String baseUrl = props.getProperty("api.base-url");
                String uploadPath = props.getProperty("api.upload.path");
                String token = props.getProperty("api.auth.suspected");
                String user = props.getProperty("api.user");

                File file = new File(App.currentFilePath);
                String boundary = "----FormBoundary" + System.currentTimeMillis();
                byte[] fileBytes = Files.readAllBytes(file.toPath());

                HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + uploadPath).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", token);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    // user 필드
                    String userPart = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"user\"\r\n\r\n"
                        + user + "\r\n";
                    os.write(userPart.getBytes(StandardCharsets.UTF_8));

                    // file 필드 (한글 파일명 RFC 5987 인코딩)
                    String encodedName = URLEncoder.encode(file.getName(), StandardCharsets.UTF_8).replace("+", "%20");
                    String filePart = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename*=UTF-8''" + encodedName + "\r\n"
                        + "Content-Type: text/plain\r\n\r\n";
                    os.write(filePart.getBytes(StandardCharsets.UTF_8));
                    os.write(fileBytes);
                    os.write("\r\n".getBytes(StandardCharsets.UTF_8));

                    // 종료 boundary
                    os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
                String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                System.out.println("=== 파일 업로드 응답 ===");
                System.out.println("Status: " + status);
                System.out.println(response);

                ObjectMapper mapper = new ObjectMapper();
                uploadFileId = mapper.readTree(response).path("id").asText("");

                callChatApi("업로드한 파일 체크", true);

            } catch (Exception e) {
                System.err.println("파일 업로드 오류: " + e.getMessage());
                Platform.runLater(() -> addBubble("[업로드 오류] " + e.getMessage(), false));
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // initialize() 또는 handleChatSearch()에서 호출
    // query를 Dify chat-messages API로 POST, 응답의 answer/conversation_id 파싱
    // conversation_id를 저장해 이후 호출에서 대화 컨텍스트 유지
    private void callChatApi(String query) {
        callChatApi(query, false);
    }

    // silent=true 면 사용자 쿼리 버블은 숨기고 AI 응답만 표시
    private void callChatApi(String query, boolean silent) {
        Thread thread = new Thread(() -> {
            try {
                Properties props = new Properties();
                try (InputStream in = getClass().getResourceAsStream("/application-dev.properties")) {
                    props.load(in);
                }

                String baseUrl = props.getProperty("api.base-url");
                String chatPath = props.getProperty("api.chat.path");
                String token = props.getProperty("api.auth.suspected");
                String user = props.getProperty("api.user");
                String responseMode = props.getProperty("api.chat.response-mode");

                ObjectMapper mapper = new ObjectMapper();
                ObjectNode bodyNode = mapper.createObjectNode();

                // inputs.uploaded_file
                ObjectNode inputsNode = bodyNode.putObject("inputs");
                if (!uploadFileId.isEmpty()) {
                    ObjectNode uploadedFile = inputsNode.putObject("uploaded_file");
                    uploadedFile.put("transfer_method", "local_file");
                    uploadedFile.put("upload_file_id", uploadFileId);
                }

                bodyNode.put("query", query);
                bodyNode.put("response_mode", responseMode);
                bodyNode.put("user", user);
                if (!conversationId.isEmpty()) {
                    bodyNode.put("conversation_id", conversationId);
                }

                // files 배열
                if (!uploadFileId.isEmpty()) {
                    ObjectNode fileEntry = mapper.createObjectNode();
                    fileEntry.put("type", "document");
                    fileEntry.put("transfer_method", "local_file");
                    fileEntry.put("upload_file_id", uploadFileId);
                    bodyNode.putArray("files").add(fileEntry);
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
                    if (!silent) addBubble(query, true);
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
