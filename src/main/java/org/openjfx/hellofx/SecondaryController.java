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

import org.json.JSONArray;

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

    // 파일 업로드 후 발급된 Dify 파일 ID
    private String uploadFileId = "";

    // 탭별 Bearer 토큰 (application-dev.properties에서 로드)
    private String tokenSuspected;
    private String tokenAnomaly;
    private String tokenInsights;

    // 탭별 conversation_id — 탭 키("suspected" / "anomaly" / "insights")로 관리
    // ConcurrentHashMap: 백그라운드 스레드에서 동시 접근해도 안전
    private final Map<String, String> conversationIds = new ConcurrentHashMap<>();

    // 현재 활성 탭 상태 (탭 전환 시 갱신)
    private VBox activeContainer;  // 현재 탭의 말풍선 컨테이너
    private String activeToken;    // 현재 탭의 API 토큰
    private String activeTabKey;   // 현재 탭의 키 ("suspected" / "anomaly" / "insights")

    @FXML
    private void initialize() {
        // 상단 브레드크럼에 "날짜_파일명" 형식으로 표시
        breadcrumbLabel.setText(App.currentDate + "_" + App.currentFile);

        // properties 파일에서 탭별 토큰 로드
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application-dev.properties")) {
            props.load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }
        tokenSuspected = props.getProperty("api.auth.suspected");
        tokenAnomaly   = props.getProperty("api.auth.anomaly");
        tokenInsights  = props.getProperty("api.auth.insights");

        // 체크하지 않은 탭은 화면에서 완전히 숨김 (공간도 차지하지 않도록 managed=false)
        tabSuspected.setVisible(App.runSuspected); tabSuspected.setManaged(App.runSuspected);
        tabAnomaly.setVisible(App.runAnomaly);     tabAnomaly.setManaged(App.runAnomaly);
        tabInsights.setVisible(App.runInsights);   tabInsights.setManaged(App.runInsights);

        // 체크된 탭 중 첫 번째를 기본 활성 탭으로 설정
        if      (App.runSuspected) setActiveTab(tabSuspected);
        else if (App.runAnomaly)   setActiveTab(tabAnomaly);
        else if (App.runInsights)  setActiveTab(tabInsights);

        // 파일 전처리 → 업로드 → 탭별 초기 분석 요청
        uploadFile();
    }

    /**
     * 선택된 로그 파일을 전처리 후 Dify에 업로드하고,
     * 체크된 탭마다 각자의 토큰으로 초기 분석 API를 호출한다.
     *
     * ✅ 수정 포인트:
     *   - 업로드 토큰을 tokenSuspected 고정 → 체크된 첫 번째 탭 토큰으로 변경
     *   - 탭별 초기 호출 시 각자의 토큰 사용 (기존과 동일하나 명시적으로 유지)
     *
     * ⚠️ Dify 앱이 탭마다 다른 워크스페이스라면
     *    uploadFileId를 공유할 수 없으므로 탭별 별도 업로드가 필요하다.
     */
    private void uploadFile() {
        Thread thread = new Thread(() -> {
            try {
                // 1. 로그 파일 전처리 (JSON 배열로 변환)
                LogPreprocessor preprocessor = new LogPreprocessor();
                JSONArray preprocessed = preprocessor.processFile(App.currentFilePath);
                String preprocessedText = preprocessed.toString(2); // pretty-print JSON

                System.out.println("=== 전처리 결과 ===");
                System.out.println(preprocessedText);

                // 2. 전처리 결과를 임시 파일로 저장 (multipart 업로드용)
                java.io.File tempFile = java.io.File.createTempFile("preprocessed_", ".json");
                java.nio.file.Files.writeString(tempFile.toPath(), preprocessedText);

                // properties 재로드 (스레드 내부라 별도 로드)
                Properties props = new Properties();
                try (InputStream in = getClass().getResourceAsStream("/application-dev.properties")) {
                    props.load(in);
                }

                String baseUrl    = props.getProperty("api.base-url");
                String uploadPath = props.getProperty("api.upload.path");
                String user       = props.getProperty("api.user");

                // ✅ [수정] 체크된 첫 번째 탭의 토큰으로 업로드
                // 이전: tokenSuspected 고정 → Anomaly/Insights만 선택 시 null 또는 권한 없어 401 발생
                String uploadToken;
                if      (App.runSuspected) uploadToken = tokenSuspected;
                else if (App.runAnomaly)   uploadToken = tokenAnomaly;
                else                       uploadToken = tokenInsights;

                // 3. multipart/form-data 형식으로 Dify /v1/files/upload 호출
                String boundary = "----FormBoundary" + System.currentTimeMillis();
                byte[] fileBytes = java.nio.file.Files.readAllBytes(tempFile.toPath());

                HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + uploadPath).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", uploadToken); // ✅ [수정] 첫 번째 탭 토큰 사용
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    // user 필드
                    String userPart = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"user\"\r\n\r\n"
                        + user + "\r\n";
                    os.write(userPart.getBytes(StandardCharsets.UTF_8));

                    // file 필드 (전처리된 JSON을 application/json 타입으로 전송)
                    String filePart = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"preprocessed.txt\"\r\n"
                        + "Content-Type: application/json\r\n\r\n";
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

                // 401 등 에러 시 Dify가 반환하는 상세 메시지 출력 (원인 파악용)
                if (status < 200 || status >= 300) {
                    System.err.println("=== 업로드 실패 — Dify 에러 body ===");
                    System.err.println(response);
                }

                // 업로드 성공 시 파일 ID 추출 (이후 chat API 호출에서 사용)
                ObjectMapper mapper = new ObjectMapper();
                uploadFileId = mapper.readTree(response).path("id").asText("");

                // 임시 파일은 JVM 종료 시 자동 삭제
                tempFile.deleteOnExit();

                // 4. 체크된 탭마다 각자의 토큰으로 초기 분석 요청
                //    ⚠️ Dify 앱이 탭마다 다른 워크스페이스라면
                //       uploadFileId 공유가 불가하므로 탭별 별도 업로드 필요
                if (App.runSuspected) callChatApi(
                    "오류는 아닌데, 오류로 추정되는 로그확인해주고 결과를 잘 보여줘",
                    true, tokenSuspected, "suspected", containerSuspected);
                if (App.runAnomaly)   callChatApi(
                    "이상 징후 확인해서 알려줘 결과를 잘 보여줘",
                    true, tokenAnomaly, "anomaly", containerAnomaly);
                if (App.runInsights)  callChatApi(
                    "어떻게 수정해야 더 좋은 로그파일을 만들 수 있을지 알려줘 결과를 잘 보여줘",
                    true, tokenInsights, "insights", containerInsights);

            } catch (Exception e) {
                System.err.println("전처리/업로드 오류: " + e.getMessage());
                if (activeContainer != null)
                    Platform.runLater(() -> addBubble("[오류] " + e.getMessage(), false, activeContainer));
            }
        });
        thread.setDaemon(true); // 메인 스레드 종료 시 함께 종료
        thread.start();
    }

    /**
     * 현재 활성 탭 기준으로 채팅 API 호출 (사용자 입력용 단축 메서드)
     */
    private void callChatApi(String query) {
        callChatApi(query, false, activeToken, activeTabKey, activeContainer);
    }

    /**
     * Dify /v1/chat-messages 호출
     *
     * @param query   전송할 질문 텍스트
     * @param silent  true = 사용자 말풍선 미표시 (초기 자동 호출 시 사용)
     * @param token   해당 탭의 Bearer 토큰
     * @param tabKey  탭 식별 키 ("suspected" / "anomaly" / "insights")
     * @param target  말풍선을 추가할 VBox 컨테이너
     *
     * 설계 원칙:
     *  - inputs.uploaded_file: 매 호출마다 포함 (Dify workflow required 필드)
     *  - files 배열: 첫 호출에만 포함 (conversation 생성 시에만 필요)
     *  - conversation_id: tabKey 기준으로 탭별 독립 유지
     */
    private void callChatApi(String query, boolean silent, String token, String tabKey, VBox target) {
        // 로딩 스피너를 해당 컨테이너에 즉시 추가
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

                // 탭별 conversation_id 조회 (없으면 빈 문자열 → 첫 호출로 판단)
                String convId     = conversationIds.getOrDefault(tabKey, "");
                boolean firstCall = convId.isEmpty();

                // 요청 body JSON 구성
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode bodyNode = mapper.createObjectNode();

                // inputs.uploaded_file: Dify workflow에서 required로 설정된 파일 변수
                ObjectNode inputsNode = bodyNode.putObject("inputs");
                if (!uploadFileId.isEmpty()) {
                    ObjectNode uploadedFile = inputsNode.putObject("uploaded_file");
                    uploadedFile.put("transfer_method", "local_file");
                    uploadedFile.put("upload_file_id", uploadFileId);
                }

                bodyNode.put("query", query);
                bodyNode.put("response_mode", responseMode); // "blocking" 또는 "streaming"
                bodyNode.put("user", user);

                // 두 번째 호출부터 conversation_id 포함 (이전 대화 맥락 유지)
                if (!firstCall) bodyNode.put("conversation_id", convId);

                // files 배열은 첫 호출에만 포함 (Dify conversation 최초 생성 시)
                if (firstCall && !uploadFileId.isEmpty()) {
                    ObjectNode fileEntry = mapper.createObjectNode();
                    fileEntry.put("type", "document");
                    fileEntry.put("transfer_method", "local_file");
                    fileEntry.put("upload_file_id", uploadFileId);
                    bodyNode.putArray("files").add(fileEntry);
                }

                String body = mapper.writeValueAsString(bodyNode);

                // Dify chat-messages API 호출
                HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + chatPath).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", token); // 탭별 토큰 사용
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                InputStream rawIs = (status >= 200 && status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

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

                // 401 등 에러 시 Dify 상세 메시지 별도 출력 (원인 파악용)
                if (status < 200 || status >= 300) {
                    System.err.println("=== chat API 실패 — Dify 에러 body ===");
                    System.err.println(response);
                }

                // 응답에서 answer와 conversation_id 추출
                var tree = mapper.readTree(response);
                String answer    = tree.path("answer").asText("(응답 없음)");
                String newConvId = tree.path("conversation_id").asText("");

                // conversation_id 갱신 (다음 호출 시 대화 맥락 유지에 사용)
                if (!newConvId.isEmpty()) conversationIds.put(tabKey, newConvId);

                Platform.runLater(() -> {
                    target.getChildren().remove(loadingRow); // 로딩 스피너 제거
                    if (!silent) addBubble(query, true, target); // 사용자 말풍선 (silent 시 생략)
                    addBubble(answer, false, target);             // AI 응답 말풍선
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

    /**
     * 말풍선 추가
     * @param isUser true = 오른쪽(사용자 질문), false = 왼쪽(AI 응답)
     */
    private void addBubble(String text, boolean isUser, VBox target) {
        Label bubble = new Label(text);
        bubble.getStyleClass().add(isUser ? "bubble-right" : "bubble-left");
        HBox row = new HBox(bubble);
        row.getStyleClass().add(isUser ? "bubble-row-right" : "bubble-row-left");
        target.getChildren().add(row);
    }

    /**
     * 탭 전환 처리
     * - 활성 탭 버튼 스타일 변경
     * - 해당 컨테이너만 표시, 나머지 숨김
     * - activeContainer / activeToken / activeTabKey 갱신
     */
    private void setActiveTab(Button active) {
        // 모든 탭 버튼을 비활성 스타일로 초기화
        for (Button btn : new Button[]{tabSuspected, tabAnomaly, tabInsights}) {
            btn.getStyleClass().setAll("tab-inactive");
        }
        active.getStyleClass().setAll("tab-active");

        // 활성 탭 컨테이너만 표시 (managed=false로 레이아웃 공간도 제거)
        containerSuspected.setVisible(active == tabSuspected); containerSuspected.setManaged(active == tabSuspected);
        containerAnomaly.setVisible(active == tabAnomaly);     containerAnomaly.setManaged(active == tabAnomaly);
        containerInsights.setVisible(active == tabInsights);   containerInsights.setManaged(active == tabInsights);

        // 현재 활성 탭 상태 갱신 (채팅 입력 시 사용)
        if      (active == tabSuspected) { activeContainer = containerSuspected; activeToken = tokenSuspected; activeTabKey = "suspected"; }
        else if (active == tabAnomaly)   { activeContainer = containerAnomaly;   activeToken = tokenAnomaly;   activeTabKey = "anomaly"; }
        else                             { activeContainer = containerInsights;   activeToken = tokenInsights;  activeTabKey = "insights"; }
    }

    @FXML private void handleTabSuspected() { setActiveTab(tabSuspected); }
    @FXML private void handleTabAnomaly()   { setActiveTab(tabAnomaly); }
    @FXML private void handleTabInsights()  { setActiveTab(tabInsights); }

    /**
     * 채팅 입력창 전송 버튼 처리
     * 현재 활성 탭으로 사용자 질문을 전송한다.
     */
    @FXML
    private void handleChatSearch() {
        String query = chatField.getText().trim();
        if (!query.isEmpty()) {
            chatField.clear();
            callChatApi(query); // 활성 탭 기준으로 호출
        }
    }

    @FXML
    private void switchToPrimary() throws IOException {
        App.setRoot("primary");
    }
}