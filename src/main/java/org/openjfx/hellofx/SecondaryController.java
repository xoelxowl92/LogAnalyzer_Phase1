package org.openjfx.hellofx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class SecondaryController {

    @FXML private Label breadcrumbLabel;
    @FXML private VBox resultsContainer;
    @FXML private VBox containerSuspected;
    @FXML private VBox containerAnomaly;
    @FXML private VBox containerInsights;
    @FXML private Button tabSuspected;
    @FXML private Button tabAnomaly;
    @FXML private Button tabInsights;
    @FXML private TextField chatField;

    private String uploadFileId = "";

    // 탭별 토큰 (properties에서 로드)
    private String tokenSuspected;
    private String tokenAnomaly;
    private String tokenInsights;

    // 탭별 conversation_id — "suspected" / "anomaly" / "insights" 키
    private final Map<String, String> conversationIds = new ConcurrentHashMap<>();

    // 현재 활성 탭 상태
    private VBox activeContainer;
    private String activeToken;
    private String activeTabKey;

    @FXML
    private void initialize() {
        breadcrumbLabel.setText(App.currentDate + "_" + App.currentFile);

        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application-dev.properties")) {
            props.load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }
        tokenSuspected = props.getProperty("api.auth.suspected");
        tokenAnomaly   = props.getProperty("api.auth.anomaly");
        tokenInsights  = props.getProperty("api.auth.insights");

        // 체크하지 않은 탭 숨김
        tabSuspected.setVisible(App.runSuspected); tabSuspected.setManaged(App.runSuspected);
        tabAnomaly.setVisible(App.runAnomaly);     tabAnomaly.setManaged(App.runAnomaly);
        tabInsights.setVisible(App.runInsights);   tabInsights.setManaged(App.runInsights);

        // 첫 번째 체크된 탭을 기본 활성 탭으로 설정
        if      (App.runSuspected) setActiveTab(tabSuspected);
        else if (App.runAnomaly)   setActiveTab(tabAnomaly);
        else if (App.runInsights)  setActiveTab(tabInsights);

        uploadFile();
    }

    // 파일을 Dify /v1/files/upload로 업로드 후 체크된 탭마다 초기 API 호출
    private void uploadFile() {
        Thread thread = new Thread(() -> {
            try {
                Properties props = new Properties();
                try (InputStream in = getClass().getResourceAsStream("/application-dev.properties")) {
                    props.load(in);
                }

                String baseUrl    = props.getProperty("api.base-url");
                String uploadPath = props.getProperty("api.upload.path");
                String user       = props.getProperty("api.user");

                File file = new File(App.currentFilePath);
                String boundary = "----FormBoundary" + System.currentTimeMillis();
                byte[] fileBytes = Files.readAllBytes(file.toPath());

                HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + uploadPath).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", tokenSuspected);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    String userPart = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"user\"\r\n\r\n"
                        + user + "\r\n";
                    os.write(userPart.getBytes(StandardCharsets.UTF_8));

                    // 파일명 한글 처리 (RFC 5987)
                    String encodedName = URLEncoder.encode(file.getName(), StandardCharsets.UTF_8).replace("+", "%20");
                    String filePart = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename*=UTF-8''" + encodedName + "\r\n"
                        + "Content-Type: text/plain\r\n\r\n";
                    os.write(filePart.getBytes(StandardCharsets.UTF_8));
                    os.write(fileBytes);
                    os.write("\r\n".getBytes(StandardCharsets.UTF_8));

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

                // 체크된 탭마다 초기 분석 요청 (사용자 버블 미표시)
                if (App.runSuspected) callChatApi("오류는 아닌데, 오류로 추정되는 로그확인해주고 결과를 잘 보여줘", true, tokenSuspected, "suspected", containerSuspected);
                if (App.runAnomaly)   callChatApi("이상 징후 확인해서 알려줘 결과를 잘 보여줘", true, tokenAnomaly,   "anomaly",   containerAnomaly);
                if (App.runInsights)  callChatApi("어떻게 수정해야 더 좋은 로그파일을 만들 수 있을지 알려줘 결과를 잘 보여줘", true, tokenInsights,  "insights",  containerInsights);

            } catch (Exception e) {
                System.err.println("파일 업로드 오류: " + e.getMessage());
                Platform.runLater(() -> addBubble("[업로드 오류] " + e.getMessage(), false, activeContainer));
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // 현재 활성 탭으로 채팅 API 호출
    private void callChatApi(String query) {
        callChatApi(query, false, activeToken, activeTabKey, activeContainer);
    }

    // Dify /v1/chat-messages 호출
    // - inputs.uploaded_file: 매 호출마다 포함 (Dify required 필드)
    // - files 배열: 첫 호출에만 포함
    // - conversation_id: tabKey 기준으로 탭별 독립 유지
    // - silent=true: 사용자 버블 미표시, AI 응답만 표시
    private void callChatApi(String query, boolean silent, String token, String tabKey, VBox target) {
        // 로딩 인디케이터 추가
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(24, 24);
        HBox loadingRow = new HBox(spinner);
        loadingRow.getStyleClass().add("bubble-row-left");
        Platform.runLater(() -> target.getChildren().add(loadingRow));

        Thread thread = new Thread(() -> {
            try {
                Properties props = new Properties();
                try (InputStream in = getClass().getResourceAsStream("/application-dev.properties")) {
                    props.load(in);
                }

                String baseUrl      = props.getProperty("api.base-url");
                String chatPath     = props.getProperty("api.chat.path");
                String user         = props.getProperty("api.user");
                String responseMode = props.getProperty("api.chat.response-mode");

                String convId     = conversationIds.getOrDefault(tabKey, "");
                boolean firstCall = convId.isEmpty();

                ObjectMapper mapper = new ObjectMapper();
                ObjectNode bodyNode = mapper.createObjectNode();

                ObjectNode inputsNode = bodyNode.putObject("inputs");
                if (!uploadFileId.isEmpty()) {
                    ObjectNode uploadedFile = inputsNode.putObject("uploaded_file");
                    uploadedFile.put("transfer_method", "local_file");
                    uploadedFile.put("upload_file_id", uploadFileId);
                }

                bodyNode.put("query", query);
                bodyNode.put("response_mode", responseMode);
                bodyNode.put("user", user);

                if (!firstCall) bodyNode.put("conversation_id", convId);

                // files 배열은 첫 호출에만 포함
                if (firstCall && !uploadFileId.isEmpty()) {
                    ObjectNode fileEntry = mapper.createObjectNode();
                    fileEntry.put("type", "document");
                    fileEntry.put("transfer_method", "local_file");
                    fileEntry.put("upload_file_id", uploadFileId);
                    bodyNode.putArray("files").add(fileEntry);
                }

                String body = mapper.writeValueAsString(bodyNode);

                HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + chatPath).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                InputStream rawIs = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
                if (rawIs == null) {
                    Platform.runLater(() -> {
                        target.getChildren().remove(loadingRow);
                        addBubble("[HTTP " + status + "] 응답 없음", false, target);
                    });
                    return;
                }
                String response = new String(rawIs.readAllBytes(), StandardCharsets.UTF_8);

                System.out.println("Status: " + status);
                System.out.println(response);

                var tree = mapper.readTree(response);
                String answer    = tree.path("answer").asText("(응답 없음)");
                String newConvId = tree.path("conversation_id").asText("");
                if (!newConvId.isEmpty()) conversationIds.put(tabKey, newConvId);

                Platform.runLater(() -> {
                    target.getChildren().remove(loadingRow);
                    if (!silent) addBubble(query, true, target);
                    addBubble(answer, false, target);
                });

            } catch (Exception e) {
                System.err.println("연결 오류: " + e.getMessage());
                Platform.runLater(() -> {
                    target.getChildren().remove(loadingRow);
                    addBubble("[오류] " + e.getMessage(), false, target);
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // 말풍선 추가 — isUser=true: 오른쪽(내 질문), false: 왼쪽(AI 응답)
    private void addBubble(String text, boolean isUser, VBox target) {
        Label bubble = new Label(text);
        bubble.getStyleClass().add(isUser ? "bubble-right" : "bubble-left");
        HBox row = new HBox(bubble);
        row.getStyleClass().add(isUser ? "bubble-row-right" : "bubble-row-left");
        target.getChildren().add(row);
    }

    // 탭 전환 — 스타일, 컨테이너 show/hide, 활성 상태 업데이트
    private void setActiveTab(Button active) {
        for (Button btn : new Button[]{tabSuspected, tabAnomaly, tabInsights}) {
            btn.getStyleClass().setAll("tab-inactive");
        }
        active.getStyleClass().setAll("tab-active");

        containerSuspected.setVisible(active == tabSuspected); containerSuspected.setManaged(active == tabSuspected);
        containerAnomaly.setVisible(active == tabAnomaly);     containerAnomaly.setManaged(active == tabAnomaly);
        containerInsights.setVisible(active == tabInsights);   containerInsights.setManaged(active == tabInsights);

        if      (active == tabSuspected) { activeContainer = containerSuspected; activeToken = tokenSuspected; activeTabKey = "suspected"; }
        else if (active == tabAnomaly)   { activeContainer = containerAnomaly;   activeToken = tokenAnomaly;   activeTabKey = "anomaly"; }
        else                             { activeContainer = containerInsights;   activeToken = tokenInsights;  activeTabKey = "insights"; }
    }

    @FXML private void handleTabSuspected() { setActiveTab(tabSuspected); }
    @FXML private void handleTabAnomaly()   { setActiveTab(tabAnomaly); }
    @FXML private void handleTabInsights()  { setActiveTab(tabInsights); }

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
