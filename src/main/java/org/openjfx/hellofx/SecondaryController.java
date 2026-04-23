package org.openjfx.hellofx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.json.JSONArray;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class SecondaryController {

    // =========================================================================
    // FXML 바인딩
    // =========================================================================

    @FXML private Label     breadcrumbLabel;
    @FXML private VBox      resultsContainer;
    @FXML private VBox      containerSuspected;
    @FXML private VBox      containerAnomaly;
    @FXML private VBox      containerInsights;
    @FXML private Button    tabSuspected;
    @FXML private Button    tabAnomaly;
    @FXML private Button    tabInsights;
    @FXML private TextField chatField;

    // =========================================================================
    // 재시도 설정
    // =========================================================================

    /** 재시도 대상 HTTP 상태코드 (일시적 서버/게이트웨이 오류) */
    private static final int[] RETRYABLE_STATUS = {502, 503, 504};

    /** 최대 재시도 횟수 */
    private static final int MAX_RETRY = 3;

    /** 지수 백오프 기본 대기시간 (ms). 재시도마다 2배 증가 */
    private static final long BASE_BACKOFF_MS = 2_000L;

    // =========================================================================
    // 상태 필드
    // =========================================================================

    /** 파일 업로드 후 발급된 Dify 파일 ID */
    private String uploadFileId = "";

    /** 탭별 Bearer 토큰 (application-dev.properties 에서 로드) */
    private String tokenSuspected;
    private String tokenAnomaly;
    private String tokenInsights;

    /**
     * 탭별 conversation_id.
     * ConcurrentHashMap: 백그라운드 스레드에서 동시 접근해도 안전.
     */
    private final Map<String, String> conversationIds = new ConcurrentHashMap<>();

    /** 현재 활성 탭 상태 (탭 전환 시 갱신) */
    private VBox   activeContainer;
    private String activeToken;
    private String activeTabKey;

    /** Jackson ObjectMapper — 재사용하여 인스턴스 생성 비용 절감 */
    private final ObjectMapper mapper = new ObjectMapper();

    // =========================================================================
    // 초기화
    // =========================================================================

    @FXML
    private void initialize() {
        breadcrumbLabel.setText(App.currentDate + "_" + App.currentFile);

        Properties props = loadProps();
        tokenSuspected = props.getProperty("api.auth.suspected");
        tokenAnomaly   = props.getProperty("api.auth.anomaly");
        tokenInsights  = props.getProperty("api.auth.insights");

        // 체크되지 않은 탭은 완전히 숨김 (레이아웃 공간도 차지하지 않음)
        setTabVisible(tabSuspected, containerSuspected, App.runSuspected);
        setTabVisible(tabAnomaly,   containerAnomaly,   App.runAnomaly);
        setTabVisible(tabInsights,  containerInsights,  App.runInsights);

        // 체크된 탭 중 첫 번째를 기본 활성 탭으로 설정
        if      (App.runSuspected) setActiveTab(tabSuspected);
        else if (App.runAnomaly)   setActiveTab(tabAnomaly);
        else if (App.runInsights)  setActiveTab(tabInsights);

        uploadFile();
    }

    // =========================================================================
    // 파일 업로드 + 초기 분석 요청
    // =========================================================================

    /**
     * 선택된 로그 파일을 전처리 후 Dify 에 업로드하고,
     * 체크된 탭마다 각자의 토큰으로 초기 분석 API 를 호출한다.
     */
    private void uploadFile() {
        Thread thread = new Thread(() -> {
            try {
                // 1. 로그 전처리
                LogPreprocessor preprocessor = new LogPreprocessor();
                JSONArray preprocessed = preprocessor.processFile(App.currentFilePath);
                String preprocessedText = preprocessed.toString(2);

                System.out.println("=== 전처리 결과 ===");
                System.out.println(preprocessedText);

                // 2. 임시 파일로 저장 (multipart 업로드용)
                File tempFile = File.createTempFile("preprocessed_", ".json");
                java.nio.file.Files.writeString(tempFile.toPath(), preprocessedText);
                tempFile.deleteOnExit();

                Properties props    = loadProps();
                String baseUrl      = props.getProperty("api.base-url");
                String uploadPath   = props.getProperty("api.upload.path");
                String user         = props.getProperty("api.user");

                // 체크된 첫 번째 탭의 토큰으로 업로드
                // (이전: tokenSuspected 고정 → Anomaly/Insights 만 선택 시 401 발생)
                String uploadToken;
                if      (App.runSuspected) uploadToken = tokenSuspected;
                else if (App.runAnomaly)   uploadToken = tokenAnomaly;
                else                       uploadToken = tokenInsights;

                // 3. multipart/form-data 업로드
                String boundary  = "----FormBoundary" + System.currentTimeMillis();
                byte[] fileBytes = java.nio.file.Files.readAllBytes(tempFile.toPath());

                HttpURLConnection conn = openConnection(baseUrl + uploadPath, "POST", uploadToken, null);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                try (OutputStream os = conn.getOutputStream()) {
                    writeMultipartField(os, boundary, "user", user);
                    writeMultipartFile (os, boundary, "file", "preprocessed.txt",
                                        "application/json", fileBytes);
                    os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }

                // 4. 업로드 응답 처리
                ApiResponse uploadResp = readResponse(conn);
                System.out.println("=== 파일 업로드 응답 ===");
                System.out.println("Status: " + uploadResp.status);
                System.out.println(uploadResp.body);

                if (!uploadResp.isSuccess()) {
                    System.err.println("=== 업로드 실패 — Dify 에러 body ===");
                    System.err.println(uploadResp.body);
                    Platform.runLater(() ->
                        addBubble("[업로드 실패 " + uploadResp.status + "] " + uploadResp.body,
                                  false, activeContainer));
                    return;
                }

                uploadFileId = mapper.readTree(uploadResp.body).path("id").asText("");

                // 5. 체크된 탭마다 초기 분석 요청
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
                Platform.runLater(() ->
                    addBubble("[오류] " + e.getMessage(), false, activeContainer));
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // =========================================================================
    // Chat API 호출 (재시도 포함)
    // =========================================================================

    /** 현재 활성 탭 기준으로 채팅 API 호출 (사용자 입력용 단축 메서드) */
    private void callChatApi(String query) {
        callChatApi(query, false, activeToken, activeTabKey, activeContainer);
    }

    /**
     * Dify /v1/chat-messages 를 호출한다.
     * 504 / 502 / 503 발생 시 지수 백오프로 최대 MAX_RETRY 회 재시도한다.
     *
     * @param query   전송할 질문 텍스트
     * @param silent  true = 사용자 말풍선 미표시 (초기 자동 호출)
     * @param token   해당 탭의 Bearer 토큰
     * @param tabKey  탭 식별 키 ("suspected" / "anomaly" / "insights")
     * @param target  말풍선을 추가할 VBox 컨테이너
     */
    private void callChatApi(String query, boolean silent,
                              String token, String tabKey, VBox target) {

        // 로딩 스피너 즉시 표시
        HBox loadingRow = makeLoadingRow();
        Platform.runLater(() -> target.getChildren().add(loadingRow));

        Thread thread = new Thread(() -> {
            String errorMsg = null;
            String answer   = null;

            try {
                answer = callWithRetry(query, token, tabKey);
            } catch (Exception e) {
                errorMsg = e.getMessage();
                System.err.println("Chat API 최종 실패: " + e.getMessage());
            }

            final String finalAnswer = answer;
            final String finalError  = errorMsg;

            Platform.runLater(() -> {
                target.getChildren().remove(loadingRow);
                if (finalError != null) {
                    addBubble("[오류] " + finalError, false, target);
                } else {
                    if (!silent) addBubble(query, true, target);
                    addBubble(finalAnswer, false, target);
                }
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 지수 백오프 방식으로 Chat API 를 최대 MAX_RETRY 회 재시도한다.
     *
     * @return AI 응답 텍스트
     * @throws Exception 재시도 소진 또는 재시도 불가 오류 시
     */
    private String callWithRetry(String query, String token, String tabKey) throws Exception {
        int  attempt = 0;
        long delay   = BASE_BACKOFF_MS;

        while (true) {
            try {
                return doChat(query, token, tabKey);

            } catch (HttpApiException e) {

                boolean retryable = isRetryableStatus(e.statusCode);
                boolean canRetry  = attempt < MAX_RETRY - 1;

                if (retryable && canRetry) {
                    attempt++;
                    System.err.printf(
                        "[DifyChat] HTTP %d — 재시도 %d/%d, %dms 대기%n",
                        e.statusCode, attempt, MAX_RETRY, delay);
                    Thread.sleep(delay);
                    delay *= 2; // 지수 백오프
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Dify chat-messages API 단건 호출.
     *
     * @return AI 응답 텍스트
     * @throws HttpApiException HTTP 오류 응답 시 (statusCode 포함)
     * @throws Exception        네트워크/파싱 오류 시
     */
    private String doChat(String query, String token, String tabKey) throws Exception {
        Properties props = loadProps();
        String baseUrl      = props.getProperty("api.base-url");
        String chatPath     = props.getProperty("api.chat.path");
        String user         = props.getProperty("api.user");
        String responseMode = props.getProperty("api.chat.response-mode");

        String  convId    = conversationIds.getOrDefault(tabKey, "");
        boolean firstCall = convId.isEmpty();

        // 요청 body 구성
        ObjectNode bodyNode   = mapper.createObjectNode();
        ObjectNode inputsNode = bodyNode.putObject("inputs");

        if (!uploadFileId.isEmpty()) {
            ObjectNode uploadedFile = inputsNode.putObject("uploaded_file");
            uploadedFile.put("transfer_method", "local_file");
            uploadedFile.put("upload_file_id",  uploadFileId);
        }

        bodyNode.put("query",         query);
        bodyNode.put("response_mode", responseMode);
        bodyNode.put("user",          user);

        if (!firstCall) bodyNode.put("conversation_id", convId);

        if (firstCall && !uploadFileId.isEmpty()) {
            ObjectNode fileEntry = mapper.createObjectNode();
            fileEntry.put("type",            "document");
            fileEntry.put("transfer_method", "local_file");
            fileEntry.put("upload_file_id",  uploadFileId);
            bodyNode.putArray("files").add(fileEntry);
        }

        // HTTP 호출
        HttpURLConnection conn = openConnection(
            baseUrl + chatPath, "POST", token, "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(mapper.writeValueAsBytes(bodyNode));
        }

        // 응답 처리
        ApiResponse resp = readResponse(conn);

        System.out.println("=== chat 응답 ===");
        System.out.println("Status: " + resp.status);
        System.out.println(resp.body);

        if (!resp.isSuccess()) {
            System.err.println("=== chat API 실패 — Dify 에러 body ===");
            System.err.println("error code: " + resp.status);
            System.err.println(resp.body);
            throw new HttpApiException(resp.status,
                buildErrorMessage(resp.status, resp.body));
        }

        // ✅ 핵심: plain text 에러 body 에 대한 안전한 JSON 파싱
        JsonNode tree   = parseJsonSafe(resp.status, resp.body);
        String answer   = tree.path("answer").asText("(응답 없음)").trim();
        String newConvId = tree.path("conversation_id").asText("");

        if (!newConvId.isEmpty()) conversationIds.put(tabKey, newConvId);

        // Dify 앱 레벨 에러 응답 처리
        // {"code": "...", "message": "...", "status": 400}
        if (answer.isEmpty() && tree.has("code") && tree.has("message")) {
            throw new HttpApiException(
                tree.path("status").asInt(resp.status),
                "Dify 앱 오류: " + tree.path("message").asText());
        }

        return answer;
    }

    // =========================================================================
    // HTTP 유틸리티
    // =========================================================================

    /**
     * HTTP 상태코드 기반 에러 메시지를 반환한다.
     * Dify 가 plain text 로 반환하는 에러("error code: 504" 등)에 대한
     * 명확한 한국어 설명을 제공하여 사용자 혼란을 줄인다.
     */
    private static String buildErrorMessage(int status, String rawBody) {
        return switch (status) {
            case 401 -> "인증 실패 (401). API Key 를 확인하세요.";
            case 403 -> "접근 거부 (403). API Key 권한을 확인하세요.";
            case 404 -> "API 경로를 찾을 수 없습니다 (404). base-url 또는 path 설정을 확인하세요.";
            case 429 -> "요청 한도 초과 (429). 잠시 후 다시 시도하세요.";
            case 502 -> "Dify Bad Gateway (502). 백엔드 서버 상태를 확인하세요.";
            case 503 -> "Dify 서비스 불가 (503). 서버 과부하 상태일 수 있습니다.";
            case 504 -> "Dify 게이트웨이 타임아웃 (504). 서버 부하 또는 LLM 응답 지연을 확인하세요.";
            default  -> "API 오류 [" + status + "]: " + rawBody;
        };
    }

    /**
     * 응답 body 를 안전하게 JSONNode 로 파싱한다.
     *
     * Dify 가 504 등 오류 시 "error code: 504" 같은 plain text 를 반환해
     * Jackson 이 파싱 실패하는 문제를 방어한다.
     *
     * @throws HttpApiException body 가 JSON 이 아닐 때
     */
    private JsonNode parseJsonSafe(int status, String body) throws HttpApiException {
        if (body == null || body.isBlank()) {
            throw new HttpApiException(status,
                "응답 body 가 비어있습니다. (status: " + status + ")");
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            // plain text 에러 → 원문을 에러 메시지에 포함해 원인 파악 가능하게 함
            throw new HttpApiException(status,
                "JSON 이 아닌 응답 수신 [" + status + "]: " + trimmed);
        }
        try {
            return mapper.readTree(trimmed);
        } catch (Exception e) {
            throw new HttpApiException(status,
                "JSON 파싱 실패: " + e.getMessage() + " / body: " + trimmed);
        }
    }

    /**
     * HttpURLConnection 을 생성하고 공통 헤더를 설정한다.
     *
     * @param url         요청 URL 문자열
     * @param method      HTTP 메서드 ("POST" 등)
     * @param token       Authorization 헤더 값 (Bearer 포함 여부는 properties 에 맞게)
     * @param contentType Content-Type 헤더 값. null 이면 설정하지 않음.
     */
    private static HttpURLConnection openConnection(String url, String method,
                                                     String token, String contentType)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);  // 연결 타임아웃 10초
        conn.setReadTimeout(120_000);    // LLM 응답 대기 최대 2분
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", token);
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
        return conn;
    }

    /**
     * HttpURLConnection 의 응답을 읽어 ApiResponse 로 반환한다.
     * 2xx → InputStream, 그 외 → ErrorStream 사용.
     * ErrorStream 이 null 인 경우(연결 자체 실패 등)도 안전하게 처리한다.
     */
    private static ApiResponse readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300)
            ? conn.getInputStream()
            : conn.getErrorStream();

        String body = "";
        if (is != null) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                body = sb.toString().trim();
            }
        }
        return new ApiResponse(status, body);
    }

    // =========================================================================
    // Multipart 유틸리티
    // =========================================================================

    private static void writeMultipartField(OutputStream os, String boundary,
                                             String name, String value) throws IOException {
        String part = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
            + value + "\r\n";
        os.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeMultipartFile(OutputStream os, String boundary,
                                            String fieldName, String filename,
                                            String contentType, byte[] data) throws IOException {
        String header = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + fieldName
            + "\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n";
        os.write(header.getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // 일반 유틸리티
    // =========================================================================

    /** application-dev.properties 를 로드하여 반환한다 */
    private Properties loadProps() {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application-dev.properties")) {
            if (in != null) props.load(in);
        } catch (Exception e) {
            System.err.println("properties 로드 실패: " + e.getMessage());
        }
        return props;
    }

    /** 재시도 대상 상태코드 여부 확인 */
    private static boolean isRetryableStatus(int status) {
        for (int s : RETRYABLE_STATUS) {
            if (s == status) return true;
        }
        return false;
    }

    /** 로딩 스피너 HBox 생성 */
    private static HBox makeLoadingRow() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(24, 24);
        HBox row = new HBox(spinner);
        row.getStyleClass().add("bubble-row-left");
        return row;
    }

    // =========================================================================
    // UI 헬퍼
    // =========================================================================

    /**
     * 말풍선 추가
     * @param isUser true = 오른쪽(사용자 질문), false = 왼쪽(AI 응답)
     */
    private void addBubble(String text, boolean isUser, VBox target) {
        Label bubble = new Label(text);
        bubble.getStyleClass().add(isUser ? "bubble-right" : "bubble-left");
        bubble.setWrapText(true);
        HBox row = new HBox(bubble);
        row.getStyleClass().add(isUser ? "bubble-row-right" : "bubble-row-left");
        target.getChildren().add(row);
    }

    /**
     * 탭 버튼과 컨테이너의 표시 여부를 함께 설정한다.
     * managed=false 로 숨길 경우 레이아웃 공간도 차지하지 않는다.
     */
    private static void setTabVisible(Button tab, VBox container, boolean visible) {
        tab.setVisible(visible);
        tab.setManaged(visible);
        container.setVisible(false);  // 초기에는 모두 숨김 (setActiveTab 에서 하나만 표시)
        container.setManaged(false);
    }

    /**
     * 탭 전환 처리.
     * - 활성 탭 버튼 스타일 변경
     * - 해당 컨테이너만 표시, 나머지 숨김
     * - activeContainer / activeToken / activeTabKey 갱신
     */
    private void setActiveTab(Button active) {
        for (Button btn : new Button[]{tabSuspected, tabAnomaly, tabInsights}) {
            btn.getStyleClass().setAll("tab-inactive");
        }
        active.getStyleClass().setAll("tab-active");

        containerSuspected.setVisible(active == tabSuspected);
        containerSuspected.setManaged(active == tabSuspected);
        containerAnomaly.setVisible(active == tabAnomaly);
        containerAnomaly.setManaged(active == tabAnomaly);
        containerInsights.setVisible(active == tabInsights);
        containerInsights.setManaged(active == tabInsights);

        if (active == tabSuspected) {
            activeContainer = containerSuspected;
            activeToken     = tokenSuspected;
            activeTabKey    = "suspected";
        } else if (active == tabAnomaly) {
            activeContainer = containerAnomaly;
            activeToken     = tokenAnomaly;
            activeTabKey    = "anomaly";
        } else {
            activeContainer = containerInsights;
            activeToken     = tokenInsights;
            activeTabKey    = "insights";
        }
    }

    // =========================================================================
    // FXML 이벤트 핸들러
    // =========================================================================

    @FXML private void handleTabSuspected() { setActiveTab(tabSuspected); }
    @FXML private void handleTabAnomaly()   { setActiveTab(tabAnomaly); }
    @FXML private void handleTabInsights()  { setActiveTab(tabInsights); }

    /** 채팅 입력창 전송 버튼 처리 — 현재 활성 탭으로 사용자 질문을 전송한다 */
    @FXML
    private void handleChatSearch() {
        String query = chatField.getText().trim();
        if (!query.isEmpty()) {
            chatField.clear();
            callChatApi(query);
        }
    }

    @FXML
    private void switchToPrimary() throws java.io.IOException {
        App.setRoot("primary");
    }

    // =========================================================================
    // 내부 타입
    // =========================================================================

    /**
     * HTTP 응답 상태코드와 body 를 묶는 값 객체.
     * isSuccess() 로 2xx 여부를 판별할 수 있다.
     */
    private static final class ApiResponse {
        final int    status;
        final String body;

        ApiResponse(int status, String body) {
            this.status = status;
            this.body   = body;
        }

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * HTTP 상태코드를 보존하는 커스텀 예외.
     * callWithRetry() 에서 재시도 가능 여부 판별에 사용한다.
     */
    private static final class HttpApiException extends Exception {
        final int statusCode;

        HttpApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}