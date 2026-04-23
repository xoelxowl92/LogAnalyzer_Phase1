package org.openjfx.hellofx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.*;

/**
 * LogPreprocessor
 *
 * 다양한 형식의 로그 파일을 읽어 AI 분석에 적합한 구조화된 JSON 배열로 변환하는 전처리기.
 *
 * 주요 처리 흐름:
 *   1. 멀티라인 로그 병합    (스택트레이스 등 여러 줄을 한 줄로 합침)
 *   2. 노이즈 로그 제거      (헬스체크, 하트비트 등 분석 불필요 항목)
 *   3. 포맷별 패턴 파싱      (JSON, Spring, Python, Node 등 8가지 형식)
 *   4. 정규화 및 토큰 치환   (IP, UUID, 비밀번호 등 가변값 마스킹)
 *   5. 이벤트 분류           (login_success, db_error, timeout 등)
 *   6. 반복/중복 로그 제거   (DuplicateFilter 위임 — 3단계 전략)
 *   7. 템플릿 추출 및 출력 JSON 조립
 *
 * 출력 필드 (JSON Object per log):
 *   sequence_id   - 처리 순서 번호 (1부터 시작)
 *   event         - 이벤트 유형 (login_success, db_error, timeout 등)
 *   level         - 로그 레벨 (INFO, WARN, ERROR 등)
 *   timestamp     - 로그 발생 시각
 *   template      - 가변값을 제거한 로그 구조 패턴
 *   template_id   - 템플릿 고유 ID
 *   frequency     - 동일 템플릿의 n번째 등장 (AI 이상 탐지 활용)
 *   normalized    - 가변값이 마스킹된 정규화 메시지
 *   tokens        - 템플릿을 공백으로 분리한 토큰 배열
 */
public class LogPreprocessor {

    // =========================================================================
    // 내부 타입 정의
    // =========================================================================

    /**
     * 로그 패턴 핸들러 함수형 인터페이스.
     *   반환값 JSONObject → 파싱 성공 (message 필드 필수)
     *   반환값 null       → 해당 로그를 드롭 (isSkipLevel() 등에 의한 명시적 제외)
     */
    @FunctionalInterface
    private interface LogHandler {
        JSONObject apply(Matcher m, String raw);
    }

    /**
     * 하나의 로그 형식을 나타내는 불변 구조체.
     * pattern : 형식 판별 정규식
     * handler : 매칭 성공 시 JSONObject 로 변환하는 람다
     */
    private static final class LogPattern {
        final Pattern    pattern;
        final LogHandler handler;

        LogPattern(Pattern p, LogHandler h) {
            this.pattern = p;
            this.handler = h;
        }
    }

    // =========================================================================
    // 정규화용 사전-컴파일 패턴
    // static final 로 선언하여 인스턴스마다 재컴파일하는 오버헤드를 방지한다.
    // 치환 순서: UUID > IP > URL > EMAIL > ID(10+) > NUM(5-9) > USER > MASKED > PATH
    // =========================================================================
    private static final Pattern RE_UUID      = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_IP        = Pattern.compile(
        "\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?::\\d+)?\\b");
    private static final Pattern RE_URL       = Pattern.compile("https?://[^\\s\"']+");
    private static final Pattern RE_EMAIL     = Pattern.compile(
        "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern RE_LONG_NUM  = Pattern.compile("\\b\\d{10,}\\b");
    private static final Pattern RE_SHORT_NUM = Pattern.compile("\\b\\d{5,9}\\b");
    private static final Pattern RE_USER      = Pattern.compile(
        "user(?:name)?=\\w+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_PASS      = Pattern.compile(
        "password=\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_TOKEN     = Pattern.compile(
        "token=\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_PATH      = Pattern.compile(
        "/(?:[\\w.%-]+/){2,}[\\w.%-]*");
    private static final Pattern RE_MULTI_SP  = Pattern.compile("\\s{2,}");

    /** 예외 클래스명 추출 패턴 */
    private static final Pattern RE_EXCEPTION_TYPE = Pattern.compile(
        "([\\w.]+(?:exception|error))", Pattern.CASE_INSENSITIVE);

    // =========================================================================
    // 이벤트 이름 상수
    // 매직 스트링을 방지하고 오타 위험을 제거한다.
    // =========================================================================
    private static final String EVT_LOGIN_SUCCESS   = "login_success";
    private static final String EVT_LOGIN_FAILED    = "login_failed";
    private static final String EVT_LOGOUT          = "logout";
    private static final String EVT_AUTH_ERROR      = "auth_error";
    private static final String EVT_ORDER_FAIL      = "order_fail";
    private static final String EVT_ORDER_SUCCESS   = "order_success";
    private static final String EVT_PAYMENT         = "payment";
    private static final String EVT_DB_ERROR        = "db_error";
    private static final String EVT_TIMEOUT         = "timeout";
    private static final String EVT_CONN_ERROR      = "connection_error";
    private static final String EVT_EXCEPTION       = "exception";
    private static final String EVT_STARTUP         = "startup";
    private static final String EVT_SHUTDOWN        = "shutdown";
    private static final String EVT_FAIL            = "fail";
    private static final String EVT_SUCCESS         = "success";
    private static final String EVT_ETC             = "etc";
    private static final String EVT_HTTP_SUCCESS    = "http_success";
    private static final String EVT_HTTP_REDIRECT   = "http_redirect";
    private static final String EVT_HTTP_CLIENT_ERR = "http_client_error";
    private static final String EVT_HTTP_SERVER_ERR = "http_server_error";
    private static final String EVT_HTTP            = "http";

    // =========================================================================
    // 인스턴스 필드
    // =========================================================================

    /** 등록된 로그 패턴 목록. 순서대로 매칭을 시도하므로 우선순위 순으로 정렬 */
    private final List<LogPattern> patterns;

    /** Drain 알고리즘 기반 로그 템플릿 추출기 */
    private final LogPatternExtractor extractor = new LogPatternExtractor();

    /**
     * 반복/중복 로그 제거 전담 필터.
     * 3단계 전략(완전 중복 → Burst 억제 → 템플릿 반복 제한)으로 동작한다.
     */
    private final DuplicateFilter duplicateFilter = new DuplicateFilter();

    /**
     * 노이즈로 간주되어 무조건 제거할 키워드 목록 (소문자 저장).
     * {@link #addNoiseKeyword(String)} 으로 런타임에 동적 추가 가능하다.
     */
    private final Set<String> noiseKeywords;

    // =========================================================================
    // 생성자
    // =========================================================================

    public LogPreprocessor() {
        this.noiseKeywords = new HashSet<>(Set.of(
            "healthcheck", "heartbeat", "metrics", "favicon.ico",
            "connection reset", "actuator/health", "ping",
            "keepalive", "robots.txt", "__pycache__", ".ds_store"
        ));
        this.patterns = buildPatterns();
    }

    /** 런타임에 노이즈 키워드를 추가한다 (소문자로 저장됨). */
    public void addNoiseKeyword(String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            noiseKeywords.add(keyword.toLowerCase());
        }
    }

    // =========================================================================
    // 패턴 등록
    // =========================================================================

    /**
     * 지원할 로그 형식의 패턴을 순서대로 등록하여 불변 리스트로 반환한다.
     * 매칭은 등록 순서대로 시도되므로 구체적인 패턴을 먼저 등록해야 한다.
     */
    private List<LogPattern> buildPatterns() {
        List<LogPattern> list = new ArrayList<>();

        // ── 패턴 1: JSON 구조 로그 ────────────────────────────────────────
        // 예: {"level":"INFO","message":"User logged in","timestamp":"2024-01-01T00:00:00"}
        // ELK 스택, Logstash, Fluentd 등 구조화 로깅 시스템에서 주로 사용
        list.add(new LogPattern(
            Pattern.compile("^\\{.*\\}$", Pattern.DOTALL),
            (m, log) -> {
                try {
                    JSONObject json  = new JSONObject(log);
                    String     level = json.optString("level", "");
                    if (isSkipLevel(level)) return null;

                    JSONObject obj = new JSONObject();
                    obj.put("message", json.optString("message", log));
                    obj.put("level",   level);
                    // 다양한 JSON 타임스탬프 필드명을 순서대로 시도
                    obj.put("timestamp", firstNonEmpty(
                        json.optString("timestamp"),
                        json.optString("time"),
                        json.optString("@timestamp")
                    ));
                    return obj;
                } catch (Exception e) {
                    return null; // JSON 파싱 실패 → 다음 패턴으로
                }
            }
        ));

        // ── 패턴 2: Spring Boot Logback (PID 포함 기본 형식) ─────────────
        // 예: 2024-01-01 12:00:00.123  INFO 12345 --- [main] c.e.MyApp : Application started
        // 그룹: (1)타임스탬프 (2)레벨 (3)스레드명 (4)로거명 (5)메시지
        list.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.\\d]*)\\s+" +
                "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+" +
                "\\[(.*?)]\\s+([\\w.$]+)\\s+:\\s+(.+)$"
            ),
            (m, log) -> {
                if (isSkipLevel(m.group(2))) return null;
                return buildLogObj(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5));
            }
        ));

        // ── 패턴 3: Simple Logback (thread + level + logger, PID 없음) ───
        // 예: 2024-01-01 12:00:00.123 [http-nio-8080-exec-1] INFO  c.e.MyApp - Request received
        // 그룹: (1)타임스탬프 (2)스레드명 (3)레벨 (4)로거명 (5)메시지
        // ※ thread(2)와 level(3)의 그룹 순서가 패턴 2와 다름에 주의
        list.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.\\d]*)\\s+\\[(.*?)]\\s+" +
                "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+([\\w.$]+)\\s+-\\s+(.+)$"
            ),
            (m, log) -> {
                if (isSkipLevel(m.group(3))) return null;
                return buildLogObj(m.group(1), m.group(3), m.group(2), m.group(4), m.group(5));
            }
        ));

        // ── 패턴 4: Python logging 모듈 기본 포맷 ────────────────────────
        // 예: 2024-01-01 12:00:00,123 - INFO - User login failed
        // 밀리초 구분자가 콤마(,)임에 주의. CRITICAL은 Python 고유 레벨(Java의 FATAL에 해당).
        // 그룹: (1)타임스탬프 (2)레벨 (3)메시지
        list.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[,.]?\\d*)\\s+-\\s+" +
                "(INFO|ERROR|WARN(?:ING)?|DEBUG|CRITICAL)\\s+-\\s+(.+)$"
            ),
            (m, log) -> {
                if (isSkipLevel(m.group(2))) return null;
                JSONObject obj = new JSONObject();
                obj.put("timestamp", m.group(1).trim());
                obj.put("level",     normalizePythonLevel(m.group(2).trim())); // CRITICAL→ERROR 등
                obj.put("message",   m.group(3).trim());
                return obj;
            }
        ));

        // ── 패턴 5: Node.js / winston 간략 포맷 ──────────────────────────
        // 예: info: Server started on port 3000
        //     error: Cannot connect to database
        // CASE_INSENSITIVE: "Info:", "INFO:" 등 대소문자 혼용 대응
        // 그룹: (1)레벨 (2)메시지
        list.add(new LogPattern(
            Pattern.compile(
                "^(info|error|warn|debug|verbose):\\s+(.+)$",
                Pattern.CASE_INSENSITIVE
            ),
            (m, log) -> {
                if (isSkipLevel(m.group(1))) return null;
                JSONObject obj = new JSONObject();
                obj.put("level",   m.group(1).toUpperCase()); // Java 레벨 표기와 통일
                obj.put("message", m.group(2).trim());
                return obj;
            }
        ));

        // ── 패턴 6: HTTP 접근 로그 (Apache/Nginx Combined Log Format) ─────
        // 예: 192.168.1.1 - - [01/Jan/2024:12:00:00 +0900] "GET /api/users HTTP/1.1" 200 1234
        // AI가 HTTP 트래픽 패턴(응답코드 분포, 엔드포인트 빈도 등)을 분석할 수 있도록
        // http_method, http_status 를 별도 필드로 추출한다.
        // 그룹: (1)클라이언트IP (2)시각 (3)메서드 (4)경로 (5)상태코드 (6)응답크기 또는 '-'
        list.add(new LogPattern(
            Pattern.compile(
                "^(\\S+)\\s+\\S+\\s+\\S+\\s+\\[(.*?)]\\s+" +
                "\"(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(\\S+)\\s+\\S+\"\\s+(\\d{3})\\s+(\\d+|-)"
            ),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                obj.put("message",     "http " + m.group(3) + " " + m.group(4) + " " + m.group(5));
                obj.put("http_method", m.group(3));
                obj.put("http_status", m.group(5));
                obj.put("timestamp",   m.group(2));
                obj.put("level",       httpStatusToLevel(m.group(5))); // 5xx→ERROR, 4xx→WARN, etc→INFO
                return obj;
            }
        ));

        // ── 패턴 7: Exception / Error 스택트레이스 ───────────────────────
        // 예: java.lang.NullPointerException: Cannot invoke method get()
        //     Caused by: java.io.IOException: Connection refused
        // 멀티라인 병합 후 첫 줄에서 예외 타입 및 메시지를 추출한다.
        // 그룹: (1)"Exception"|"Error"|"Caused by" (2)예외 메시지
        list.add(new LogPattern(
            Pattern.compile(".*(Exception|Error|Caused by):\\s*(.*)"),
            (m, log) -> {
                String exMsg = m.group(2).trim();
                JSONObject obj = new JSONObject();
                obj.put("message", m.group(1) + ": " + (exMsg.isEmpty() ? "unknown" : exMsg));
                obj.put("level",   "ERROR"); // 예외는 항상 ERROR 레벨로 취급
                return obj;
            }
        ));

        // ── 패턴 8: Key=Value 구조 로그 ──────────────────────────────────
        // 예: status=success, user=admin, duration=123ms
        // 레거시 시스템이나 커스텀 로거에서 사용하는 단순 구조화 형식
        list.add(new LogPattern(
            Pattern.compile("^(\\w+=[^,\\s]+(,\\s*\\w+=[^,\\s]+)*)$"),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                obj.put("message", log.trim());
                return obj;
            }
        ));

        return Collections.unmodifiableList(list);
    }

    // =========================================================================
    // 메인 처리 메서드
    // =========================================================================

    /**
     * 로그 파일을 읽어 전처리된 JSON 배열로 반환한다.
     *
     * @param filePath 처리할 로그 파일 경로
     * @return AI 분석용 구조화 로그 JSON 배열
     * @throws IllegalArgumentException filePath 가 null 이거나 공백일 때
     */
    public JSONArray processFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath must not be null or blank");
        }

        duplicateFilter.reset();

        JSONArray result = new JSONArray();
        int seq = 1;

        try {
            List<String> lines  = Files.readAllLines(Paths.get(filePath));
            List<String> merged = mergeLogs(lines);

            for (String log : merged) {
                JSONObject entry = processOne(log, seq);
                if (entry != null) {
                    result.put(entry);
                    seq++;
                }
            }

        } catch (IOException e) {
            System.err.printf("[LogPreprocessor] 파일 읽기 실패: %s — %s%n",
                filePath, e.getMessage());
        }

        return result;
    }

    /**
     * 단일 로그 문자열을 전처리하여 출력 JSON 항목을 반환한다.
     * 드롭 조건을 만족하면 null 을 반환한다.
     *
     * processFile() 의 루프 본문을 분리하여 가독성과 단위 테스트 편의성을 높인다.
     */
    private JSONObject processOne(String log, int seq) {

        // ① 빈 줄 스킵
        if (log.isBlank()) return null;

        // ② 노이즈 제거 (헬스체크, 하트비트 등)
        if (isNoise(log)) return null;

        // ③ 패턴 매칭 및 파싱 (실패 시 fallback 처리)
        JSONObject parsed = parse(log);
        if (parsed == null) return null; // isSkipLevel()에 의한 명시적 드롭

        String normalized = parsed.optString("normalized", "");
        String level      = parsed.optString("level", "");

        // ④ 의미 없는 메시지 제거 (너무 짧거나 무의미한 메시지)
        if (normalized.length() < 5 || normalized.equalsIgnoreCase("ok")) return null;

        // ⑤ 템플릿 추출
        //    정규화된 메시지에서 가변값을 <*> 로 치환한 구조 패턴을 추출한다.
        //    예) "user <USER> login failed from <IP>" → "user <*> login failed from <*>"
        String template   = extractor.addLog(normalized);
        int    templateId = extractor.getTemplateId(template);

        // ⑥ 반복/중복 필터 적용
        //    3단계 전략: 완전 중복 → Burst 억제 → 템플릿 반복 제한
        DuplicateFilter.FilterResult filterResult =
            duplicateFilter.check(normalized, template, level);
        if (filterResult != DuplicateFilter.FilterResult.PASS) return null;

        // ⑦ 최종 출력 JSON 조립
        int frequency = duplicateFilter.getTemplateFrequency(template);

        JSONObject ai = new JSONObject();
        ai.put("sequence_id", seq);
        ai.put("event",       parsed.optString("event", EVT_ETC));
        ai.put("level",       level);
        ai.put("timestamp",   parsed.optString("timestamp", ""));
        ai.put("template",    template);
        ai.put("template_id", templateId);
        ai.put("frequency",   frequency);
        ai.put("normalized",  normalized);
        ai.put("tokens",      new JSONArray(Arrays.asList(template.split("\\s+"))));

        return ai;
    }

    // =========================================================================
    // 파싱
    // =========================================================================

    /**
     * 단일 로그 문자열을 파싱하여 JSONObject 로 변환한다.
     *
     * 등록된 패턴을 순서대로 시도하며, 매칭되는 패턴이 없으면 fallback() 을 호출한다.
     * 패턴 핸들러가 null 을 반환하면 해당 로그를 드롭하기 위해 null 을 반환한다.
     * 파싱 성공 시 enrich() 를 호출하여 정규화 및 이벤트 분류를 수행한다.
     *
     * @param log 파싱할 단일 로그 문자열
     * @return 파싱 + 정규화된 JSONObject, 드롭 대상이면 null
     */
    private JSONObject parse(String log) {
        for (LogPattern lp : patterns) {
            Matcher m = lp.pattern.matcher(log);
            if (m.find()) {
                JSONObject obj = lp.handler.apply(m, log);
                if (obj == null) return null;
                enrich(obj);
                return obj;
            }
        }
        return fallback(log);
    }

    // =========================================================================
    // 멀티라인 병합
    // =========================================================================

    /**
     * 여러 줄로 분산된 로그를 논리적 단위로 병합한다.
     *
     * 스택트레이스, 멀티라인 메시지 등은 여러 줄에 걸쳐 있으나
     * 하나의 로그 이벤트이므로, 새 로그 시작 패턴이 나올 때까지 이전 줄을 이어붙인다.
     *
     * 예:
     *   2024-01-01 ERROR - DB connection failed     ← 새 로그 시작
     *   java.sql.SQLException: Timeout              ← 병합 대상
     *       at com.example.DB.connect(DB.java:42)   ← 병합 대상
     *   2024-01-01 INFO - Server started            ← 새 로그 시작 → 이전 것 완성
     *
     * @param lines 파일에서 읽은 원본 라인 목록
     * @return 병합된 로그 문자열 목록
     */
    private List<String> mergeLogs(List<String> lines) {
        List<String>  result  = new ArrayList<>(lines.size());
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (isNewLogStart(line) && current.length() > 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(line).append(' ');
        }
        if (current.length() > 0) result.add(current.toString().trim());

        return result;
    }

    /**
     * 해당 줄이 새로운 로그의 시작인지 판별한다.
     *
     * 지원하는 새 로그 시작 패턴:
     *   - 타임스탬프로 시작  (YYYY-MM-DD 또는 YYYY-MM-DDThh:mm:ss)
     *   - JSON 객체로 시작   ({...)
     *   - Node.js 레벨 접두어 (info:, error: 등)
     *   - HTTP 접근 로그 형식 (IP - - [날짜] ...)
     *   - Key=Value 형식
     *
     * @param line 검사할 줄
     * @return 새 로그 시작이면 true
     */
    private boolean isNewLogStart(String line) {
        return line.matches("^\\d{4}-\\d{2}-\\d{2}[T ].*")             // 타임스탬프 시작
            || line.startsWith("{")                                      // JSON (정규식보다 빠른 startsWith)
            || line.matches("(?i)^(info|error|warn|debug|verbose):.*")  // Node.js 레벨
            || line.matches("^\\S+\\s+\\S+\\s+\\S+\\s+\\[.*")          // HTTP 접근 로그
            || line.matches("^\\w+=.*");                                 // Key=Value
    }

    // =========================================================================
    // enrich: 정규화 + 이벤트 분류
    // =========================================================================

    /**
     * 파싱된 JSONObject 에 정규화(normalized)와 이벤트 분류(event)를 수행한다.
     * message 필드를 가공하여 normalized 와 event 필드를 추가한다.
     *
     * @param obj in-place 로 수정됨 (message 필드 필수)
     */
    private void enrich(JSONObject obj) {
        String raw        = obj.optString("message", "").toLowerCase().trim();
        String normalized = normalizeMessage(raw);

        obj.put("normalized", normalized);
        obj.put("event",      classifyEvent(obj, normalized));
    }

    /**
     * 로그 메시지의 가변값을 의미 태그로 치환하여 정규화된 문자열을 반환한다.
     *
     * 치환 순서 (더 구체적인 패턴을 먼저 처리해야 오탐 없이 정확히 치환됨):
     *   UUID → &lt;UUID&gt;     IP → &lt;IP&gt;     URL → &lt;URL&gt;
     *   EMAIL → &lt;EMAIL&gt;   ID(10+자리) → &lt;ID&gt;   NUM(5~9자리) → &lt;NUM&gt;
     *   user= → &lt;USER&gt;   password= / token= → &lt;MASKED&gt;   경로 → &lt;PATH&gt;
     *
     * Exception 메시지는 단순화하여 템플릿 추출 효율을 높인다.
     * 예: "java.lang.NullPointerException: ..." → "exception occurred: nullpointerexception"
     */
    private String normalizeMessage(String raw) {
        if (raw.contains("exception") || raw.contains("caused by")) {
            raw = "exception occurred: " + extractExceptionType(raw);
        }

        raw = RE_UUID.     matcher(raw).replaceAll("<UUID>");
        raw = RE_IP.       matcher(raw).replaceAll("<IP>");
        raw = RE_URL.      matcher(raw).replaceAll("<URL>");
        raw = RE_EMAIL.    matcher(raw).replaceAll("<EMAIL>");
        raw = RE_LONG_NUM. matcher(raw).replaceAll("<ID>");
        raw = RE_SHORT_NUM.matcher(raw).replaceAll("<NUM>");
        raw = RE_USER.     matcher(raw).replaceAll("user=<USER>");
        raw = RE_PASS.     matcher(raw).replaceAll("password=<MASKED>");
        raw = RE_TOKEN.    matcher(raw).replaceAll("token=<MASKED>");
        raw = RE_PATH.     matcher(raw).replaceAll("<PATH>");
        raw = RE_MULTI_SP. matcher(raw).replaceAll(" ").trim();

        return raw;
    }

    /**
     * 정규화된 메시지와 파싱 컨텍스트를 바탕으로 이벤트 유형을 결정한다.
     *
     * 우선순위: 구체적(도메인) 이벤트 → 기술적 이벤트 → 생명주기 → 일반 성공/실패 → 기타
     *
     * @param obj 파싱된 JSON (http_status 등 추가 필드 참조용)
     * @param msg 정규화된 소문자 메시지
     * @return 이벤트 유형 문자열
     */
    private String classifyEvent(JSONObject obj, String msg) {

        // ── 로그인 / 로그아웃 ─────────────────────────────────────────────
        if (msg.contains("login")) {
            if (matchesAny(msg, "success", "succeed", "authenticated")) return EVT_LOGIN_SUCCESS;
            if (matchesAny(msg, "fail", "error", "invalid", "denied"))  return EVT_LOGIN_FAILED;
        }
        if (msg.contains("logout")) return EVT_LOGOUT;

        // ── 인증 / 권한 ───────────────────────────────────────────────────
        if (matchesAny(msg, "unauthorized", "forbidden", "401", "403")) return EVT_AUTH_ERROR;

        // ── 주문 ──────────────────────────────────────────────────────────
        if (msg.contains("order")) {
            if (matchesAny(msg, "fail", "error", "cancel"))       return EVT_ORDER_FAIL;
            if (matchesAny(msg, "success", "complete", "placed")) return EVT_ORDER_SUCCESS;
        }

        // ── 결제 ──────────────────────────────────────────────────────────
        if (matchesAny(msg, "payment", "billing", "charge", "refund")) return EVT_PAYMENT;

        // ── 기술적 이벤트 ─────────────────────────────────────────────────
        if (matchesAny(msg, "database", "db ", "sql", "jdbc", "query")) return EVT_DB_ERROR;
        if (msg.contains("timeout"))                                      return EVT_TIMEOUT;
        if (matchesAny(msg, "connection refused", "connection error", "connect failed"))
                                                                          return EVT_CONN_ERROR;
        if (matchesAny(msg, "exception occurred", "caused by", "stack trace"))
                                                                          return EVT_EXCEPTION;

        // ── HTTP ──────────────────────────────────────────────────────────
        if (msg.startsWith("http ")) {
            return classifyHttpEvent(obj.optString("http_status", ""));
        }

        // ── 생명주기 ──────────────────────────────────────────────────────
        if (matchesAny(msg, "start", "started", "initializ", "boot"))   return EVT_STARTUP;
        if (matchesAny(msg, "stop", "stopped", "shutdown", "destroy"))   return EVT_SHUTDOWN;

        // ── 일반 성공 / 실패 ──────────────────────────────────────────────
        if (matchesAny(msg, "fail", "error"))                            return EVT_FAIL;
        if (matchesAny(msg, "success", "succeed", "complete"))           return EVT_SUCCESS;

        return EVT_ETC;
    }

    // =========================================================================
    // Fallback
    // =========================================================================

    /**
     * 어떤 패턴에도 매칭되지 않은 로그를 처리하는 최후 수단(fallback).
     * 원본 로그 전체를 message 로 설정하고 enrich() 를 통해 정규화 및 이벤트 분류를 시도한다.
     */
    private JSONObject fallback(String log) {
        JSONObject obj = new JSONObject();
        obj.put("message", log.trim());
        enrich(obj);
        return obj;
    }

    // =========================================================================
    // 유틸리티 메서드
    // =========================================================================

    /**
     * 노이즈 여부를 판별한다.
     * noiseKeywords 중 하나라도 포함되면 노이즈로 간주하며, 대소문자 구분 없이 검사한다.
     */
    private boolean isNoise(String log) {
        String lower = log.toLowerCase();
        return noiseKeywords.stream().anyMatch(lower::contains);
    }

    /**
     * 해당 로그 레벨을 분석 대상에서 제외해야 하는지 판별한다.
     * DEBUG / TRACE / VERBOSE 는 개발 디버깅용으로 AI 분석에 노이즈가 될 수 있으므로 제외한다.
     *
     * @param level 로그 레벨 문자열 (대소문자 무관)
     * @return 제외 대상이면 true
     */
    private static boolean isSkipLevel(String level) {
        String l = level.toUpperCase();
        return l.equals("DEBUG") || l.equals("TRACE") || l.equals("VERBOSE");
    }

    /**
     * text 에 keywords 중 하나라도 포함되면 true 를 반환한다.
     */
    private static boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /**
     * HTTP 상태 코드를 기반으로 로그 레벨을 결정한다.
     *   5xx → ERROR (서버 오류)
     *   4xx → WARN  (클라이언트 오류)
     *   그 외 → INFO
     */
    private static String httpStatusToLevel(String status) {
        if (status.startsWith("5")) return "ERROR";
        if (status.startsWith("4")) return "WARN";
        return "INFO";
    }

    /**
     * HTTP 상태 코드를 기반으로 이벤트 유형을 세분화한다.
     *   2xx → http_success       (정상 응답)
     *   3xx → http_redirect      (리다이렉트)
     *   4xx → http_client_error  (클라이언트 오류: 404, 403 등)
     *   5xx → http_server_error  (서버 오류: 500 등)
     */
    private static String classifyHttpEvent(String status) {
        if (status.startsWith("2")) return EVT_HTTP_SUCCESS;
        if (status.startsWith("3")) return EVT_HTTP_REDIRECT;
        if (status.startsWith("4")) return EVT_HTTP_CLIENT_ERR;
        if (status.startsWith("5")) return EVT_HTTP_SERVER_ERR;
        return EVT_HTTP;
    }

    /**
     * Python 고유 로그 레벨을 Java/표준 레벨로 정규화한다.
     *   CRITICAL → ERROR  (Python의 치명 오류 = Java의 ERROR/FATAL)
     *   WARNING  → WARN   (Python은 "WARNING", Java는 "WARN" 표기)
     *   그 외는 그대로 대문자 반환
     */
    private static String normalizePythonLevel(String level) {
        return switch (level.toUpperCase()) {
            case "CRITICAL" -> "ERROR";
            case "WARNING"  -> "WARN";
            default         -> level.toUpperCase();
        };
    }

    /**
     * 예외 메시지에서 예외 클래스 이름만 추출하여 반환한다.
     * 패키지 경로를 제거하고 소문자로 통일한다.
     *
     * 예:
     *   "java.lang.NullPointerException: ..." → "nullpointerexception"
     *   "org.hibernate.HibernateException"    → "hibernateexception"
     *
     * @param msg 예외가 포함된 로그 메시지 (소문자 변환 상태)
     * @return 예외 클래스 단순명 (소문자), 추출 실패 시 "unknown"
     */
    private static String extractExceptionType(String msg) {
        Matcher m = RE_EXCEPTION_TYPE.matcher(msg);
        if (m.find()) {
            String full    = m.group(1);
            int    lastDot = full.lastIndexOf('.');
            return lastDot >= 0
                ? full.substring(lastDot + 1).toLowerCase()
                : full.toLowerCase();
        }
        return "unknown";
    }

    // =========================================================================
    // 헬퍼 메서드
    // =========================================================================

    /**
     * Spring Logback 패턴 공통 필드를 채운 JSONObject 를 생성한다.
     * 패턴 2, 패턴 3 의 중복 코드를 줄이기 위해 사용한다.
     */
    private static JSONObject buildLogObj(String timestamp, String level,
                                          String thread, String logger, String message) {
        JSONObject obj = new JSONObject();
        obj.put("timestamp", timestamp.trim());
        obj.put("level",     level.trim());
        obj.put("thread",    thread.trim());
        obj.put("logger",    logger.trim());
        obj.put("message",   message.trim());
        return obj;
    }

    /**
     * 주어진 문자열 중 첫 번째로 비어있지 않은 값을 반환한다.
     * 모두 비어있으면 빈 문자열을 반환한다.
     *
     * JSON 패턴에서 타임스탬프 필드명이 시스템마다 다를 때 폴백 탐색에 사용한다.
     */
    private static String firstNonEmpty(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isEmpty()) return s;
        }
        return "";
    }
}