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
 *   sequence_id    - 처리 순서 번호 (1부터 시작)
 *   event          - 이벤트 유형 (login_success, db_error, timeout 등)
 *   level          - 로그 레벨 (INFO, WARN, ERROR 등)
 *   timestamp      - 로그 발생 시각
 *   template       - 가변값을 제거한 로그 구조 패턴
 *   template_id    - 템플릿 고유 ID
 *   frequency      - 동일 템플릿의 n번째 등장 (AI 이상 탐지 활용)
 *   normalized     - 가변값이 마스킹된 정규화 메시지
 *   tokens         - 템플릿을 공백으로 분리한 토큰 배열
 *   drop_reason    - 드롭된 경우만 포함 (디버그 모드에서 활용 가능)
 */
public class LogPreprocessor {

    // =========================================================================
    // 내부 클래스: 로그 패턴 + 핸들러 쌍
    // =========================================================================

    /**
     * 하나의 로그 형식을 표현하는 구조체.
     *   pattern : 해당 형식을 판별하는 정규식
     *   handler : 매칭 성공 시 JSONObject 로 변환하는 람다 함수
     *
     * 핸들러 반환값:
     *   JSONObject → 파싱 성공 (message 필드 필수)
     *   null       → 해당 로그를 스킵 (예: DEBUG/TRACE 레벨 필터링)
     */
    private static class LogPattern {
        Pattern pattern;
        Handler handler;

        interface Handler {
            JSONObject apply(Matcher m, String log);
        }

        LogPattern(Pattern p, Handler h) {
            this.pattern = p;
            this.handler = h;
        }
    }

    // =========================================================================
    // 필드
    // =========================================================================

    /** 등록된 로그 패턴 목록. 순서대로 매칭을 시도하므로 우선순위 순으로 정렬 */
    private final List<LogPattern> patterns = new ArrayList<>();

    /** Drain 알고리즘 기반 로그 템플릿 추출기 */
    private final LogPatternExtractor extractor = new LogPatternExtractor();

    /**
     * 반복/중복 로그 제거 전담 필터.
     * 3단계 전략(완전 중복 → Burst → 템플릿 반복 제한)으로 동작한다.
     * 상세 동작은 DuplicateFilter 클래스 참조.
     */
    private final DuplicateFilter duplicateFilter = new DuplicateFilter();

    /**
     * 노이즈로 간주되어 무조건 제거할 키워드 목록.
     * 로그 본문에 해당 키워드가 포함되면 분석 대상에서 제외한다.
     *
     * 추가가 필요하면 이 Set을 인스턴스 필드(HashSet)로 변경하고
     * addNoiseKeyword() 메서드를 통해 런타임에 동적으로 추가할 수 있다.
     */
    private static final Set<String> NOISE_KEYWORDS = Set.of(
        "healthcheck",      // 서비스 상태 체크 (분석 불필요)
        "heartbeat",        // 생존 신호
        "metrics",          // 지표 수집 로그
        "favicon.ico",      // 브라우저 자동 요청
        "connection reset", // TCP 레벨 리셋 (애플리케이션 무관)
        "actuator/health",  // Spring Actuator 헬스 엔드포인트
        "ping",             // 연결 확인용 핑
        "keepalive",        // 연결 유지 신호
        "robots.txt",       // 크롤러 자동 요청
        "__pycache__",      // Python 캐시 관련 로그
        ".DS_Store"         // macOS 파일시스템 메타데이터
    );

    // =========================================================================
    // 생성자: 로그 패턴 등록
    // =========================================================================

    /**
     * 생성자에서 지원할 로그 형식의 패턴을 순서대로 등록한다.
     * 매칭은 등록 순서대로 시도되므로 구체적인 패턴을 먼저 등록해야 한다.
     */
    public LogPreprocessor() {

        // ── 패턴 1: JSON 구조 로그 ────────────────────────────────────────
        // 예: {"level":"INFO","message":"User logged in","timestamp":"2024-01-01T00:00:00"}
        // ELK 스택, Logstash, Fluentd 등 구조화 로깅 시스템에서 주로 사용
        patterns.add(new LogPattern(
            Pattern.compile("^\\{.*\\}$", Pattern.DOTALL),
            (m, log) -> {
                try {
                    JSONObject json = new JSONObject(log);
                    String level = json.optString("level", "");

                    // DEBUG/TRACE 레벨이면 스킵
                    if (isSkip(level)) return null;

                    JSONObject obj = new JSONObject();
                    // "message" 키가 없으면 원본 로그 전체를 메시지로 사용
                    obj.put("message", json.optString("message", log));
                    return obj;
                } catch (Exception e) {
                    // JSON 파싱 실패 → 다음 패턴으로 넘어가도록 null 반환
                    return null;
                }
            }
        ));

        // ── 패턴 2: Spring Boot Logback (PID 포함 기본 형식) ─────────────
        // 예: 2024-01-01 12:00:00.123  INFO 12345 --- [main] c.e.MyApp : Application started
        // 그룹: (1)타임스탬프 (2)레벨 (3)스레드명 (4)로거명 (5)메시지
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.\\d]*)\\s+" +
                "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+" +
                "\\[(.*?)\\]\\s+([\\w\\.$]+)\\s+:\\s+(.+)$"
            ),
            (m, log) -> {
                if (isSkip(m.group(2))) return null;

                JSONObject obj = new JSONObject();
                obj.put("timestamp", m.group(1).trim()); // 예: "2024-01-01 12:00:00.123"
                obj.put("level",     m.group(2).trim()); // 예: "INFO"
                obj.put("thread",    m.group(3).trim()); // 예: "main"
                obj.put("logger",    m.group(4).trim()); // 예: "com.example.MyApp"
                obj.put("message",   m.group(5).trim()); // 예: "Application started"
                return obj;
            }
        ));

        // ── 패턴 3: Simple Logback (thread + level + logger, PID 없음) ───
        // 예: 2024-01-01 12:00:00.123 [http-nio-8080-exec-1] INFO  c.e.MyApp - Request received
        // 그룹: (1)타임스탬프 (2)스레드명 (3)레벨 (4)로거명 (5)메시지
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.\\d]*)\\s+\\[(.*?)\\]\\s+" +
                "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+([\\w\\.$]+)\\s+-\\s+(.+)$"
            ),
            (m, log) -> {
                if (isSkip(m.group(3))) return null;

                JSONObject obj = new JSONObject();
                obj.put("timestamp", m.group(1).trim());
                obj.put("level",     m.group(3).trim()); // ※ 패턴2와 그룹 순서가 다름에 주의
                obj.put("thread",    m.group(2).trim());
                obj.put("logger",    m.group(4).trim());
                obj.put("message",   m.group(5).trim());
                return obj;
            }
        ));

        // ── 패턴 4: Python logging 모듈 기본 포맷 ────────────────────────
        // 예: 2024-01-01 12:00:00,123 - INFO - User login failed
        // Python의 경우 밀리초 구분자가 콤마(,)임에 주의
        // CRITICAL은 Python 고유 레벨 (Java의 FATAL에 해당)
        // 그룹: (1)타임스탬프 (2)레벨 (3)메시지
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[,.]?\\d*)\\s+-\\s+" +
                "(INFO|ERROR|WARN(?:ING)?|DEBUG|CRITICAL)\\s+-\\s+(.+)$"
            ),
            (m, log) -> {
                if (isSkip(m.group(2))) return null;

                JSONObject obj = new JSONObject();
                obj.put("timestamp", m.group(1).trim());
                obj.put("level",     m.group(2).trim());
                obj.put("message",   m.group(3).trim());
                return obj;
            }
        ));

        // ── 패턴 5: Node.js / winston 간략 포맷 ──────────────────────────
        // 예: info: Server started on port 3000
        //     error: Cannot connect to database
        // CASE_INSENSITIVE: "Info:", "INFO:" 등 대소문자 혼용 대응
        // 그룹: (1)레벨 (2)메시지
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(info|error|warn|debug|verbose):\\s+(.+)$",
                Pattern.CASE_INSENSITIVE
            ),
            (m, log) -> {
                if (isSkip(m.group(1))) return null;

                JSONObject obj = new JSONObject();
                obj.put("level",   m.group(1).toLowerCase()); // 소문자로 통일
                obj.put("message", m.group(2).trim());
                return obj;
            }
        ));

        // ── 패턴 6: HTTP 접근 로그 (Apache/Nginx Combined Log Format) ─────
        // 예: 192.168.1.1 - - [01/Jan/2024:12:00:00 +0900] "GET /api/users HTTP/1.1" 200 1234
        // AI가 HTTP 트래픽 패턴(응답코드 분포, 엔드포인트 빈도 등)을 분석할 수 있도록
        // http_method, http_status 를 별도 필드로 추출
        // 그룹: (1)클라이언트IP (2)시각 (3)메서드 (4)경로 (5)상태코드 (6)응답크기
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\S+)\\s+\\S+\\s+\\S+\\s+\\[(.*?)\\]\\s+" +
                "\"(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(\\S+)\\s+\\S+\"\\s+(\\d{3})\\s+(\\d+)"
            ),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                // "http GET /api/users 200" 형태로 정규화
                obj.put("message",     "http " + m.group(3) + " " + m.group(4) + " " + m.group(5));
                obj.put("http_method", m.group(3)); // classifyHttpEvent() 에서 이벤트 세분화에 사용
                obj.put("http_status", m.group(5)); // 2xx/3xx/4xx/5xx 분류용
                return obj;
            }
        ));

        // ── 패턴 7: Exception / Error 스택트레이스 ───────────────────────
        // 예: java.lang.NullPointerException: Cannot invoke method get()
        //     Caused by: java.io.IOException: Connection refused
        // 멀티라인 병합 후 첫 줄에서 예외 타입 및 메시지를 추출
        // 그룹: (1)"Exception"|"Error"|"Caused by" (2)예외 메시지
        patterns.add(new LogPattern(
            Pattern.compile(".*(Exception|Error|Caused by):\\s*(.*)"),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                String exType = m.group(1);
                String exMsg  = m.group(2).trim();
                // 메시지가 없으면 "unknown" 으로 표시
                obj.put("message", exType + ": " + (exMsg.isEmpty() ? "unknown" : exMsg));
                return obj;
            }
        ));

        // ── 패턴 8: Key=Value 구조 로그 ──────────────────────────────────
        // 예: status=success, user=admin, duration=123ms
        // 일부 레거시 시스템이나 커스텀 로거에서 사용하는 단순 구조화 형식
        patterns.add(new LogPattern(
            Pattern.compile("^(\\w+=[^,\\s]+(,\\s*\\w+=[^,\\s]+)*)$"),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                obj.put("message", log.trim());
                return obj;
            }
        ));
    }

    // =========================================================================
    // 메인 처리 메서드
    // =========================================================================

    /**
     * 로그 파일을 읽어 전처리된 JSON 배열로 반환한다.
     *
     * @param filePath 처리할 로그 파일 경로
     * @return AI 분석용 구조화 로그 JSON 배열
     */
    public JSONArray processFile(String filePath) {

        // 이전 호출의 상태가 남아있지 않도록 초기화
        duplicateFilter.reset();

        JSONArray result = new JSONArray();
        int seq = 1; // sequence_id (1부터 순번 부여)

        try {
            List<String> lines  = Files.readAllLines(Paths.get(filePath));
            List<String> merged = mergeLogs(lines); // 멀티라인 로그 병합

            for (String log : merged) {

                // ① 빈 줄 스킵
                if (log.trim().isEmpty()) continue;

                // ② 노이즈 제거 (헬스체크, 하트비트 등)
                if (isNoise(log)) continue;

                // ③ 패턴 매칭 및 파싱 (실패 시 fallback 처리)
                JSONObject parsed = parse(log);
                if (parsed == null) continue; // isSkip()에 의한 명시적 드롭

                String normalized = parsed.optString("normalized", "");
                String level      = parsed.optString("level", "");

                // ④ 의미 없는 로그 제거 (너무 짧거나 무의미한 메시지)
                if (normalized.length() < 5) continue;
                if (normalized.equals("ok")) continue;

                // ⑤ 템플릿 추출
                //    정규화된 메시지에서 가변값을 <*> 로 치환한 구조 패턴을 추출한다.
                //    예) "user <USER> login failed from <IP>" → "user <*> login failed from <*>"
                String template   = extractor.addLog(normalized);
                int    templateId = extractor.getTemplateId(template);

                // ⑥ 반복/중복 필터 적용 (DuplicateFilter 에 위임)
                //    3단계 전략: 완전 중복 → Burst 억제 → 템플릿 반복 제한
                DuplicateFilter.FilterResult filterResult =
                    duplicateFilter.check(normalized, template, level);

                if (filterResult != DuplicateFilter.FilterResult.PASS) {
                    // 드롭 이유를 로그로 남기고 싶으면 아래 주석 해제
                    // System.out.printf("[DROPPED:%s] %s%n", filterResult, normalized);
                    continue;
                }

                // ⑦ 최종 출력 JSON 조립
                int frequency = duplicateFilter.getTemplateFrequency(template);

                JSONObject ai = new JSONObject();
                ai.put("sequence_id",  seq++);
                ai.put("event",        parsed.optString("event", "etc")); // 이벤트 유형 레이블
                ai.put("level",        level);                            // 로그 레벨
                ai.put("timestamp",    parsed.optString("timestamp", "")); // 원본 타임스탬프
                ai.put("template",     template);                          // 구조 패턴
                ai.put("template_id",  templateId);                        // 템플릿 고유 ID
                ai.put("frequency",    frequency);                         // 이 템플릿의 n번째 등장
                ai.put("normalized",   normalized);                        // 마스킹된 메시지
                ai.put("tokens",       new JSONArray(                      // 토큰 배열 (NLP 모델용)
                    Arrays.asList(template.split("\\s+"))));

                result.put(ai);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }

    // =========================================================================
    // 내부 메서드
    // =========================================================================

    /**
     * 단일 로그 문자열을 파싱하여 JSONObject 로 변환한다.
     *
     * 등록된 패턴을 순서대로 시도하며, 매칭되는 패턴이 없으면 fallback() 을 호출한다.
     * 패턴 핸들러가 null 을 반환하면 해당 로그를 드롭하기 위해 null 을 반환한다.
     * 파싱 성공 시 enrich()를 호출하여 정규화 및 이벤트 분류를 수행한다.
     *
     * @param log 파싱할 단일 로그 문자열
     * @return 파싱 + 정규화된 JSONObject, 드롭 대상이면 null
     */
    private JSONObject parse(String log) {
        for (LogPattern lp : patterns) {
            Matcher m = lp.pattern.matcher(log);
            if (m.find()) {
                JSONObject obj = lp.handler.apply(m, log);
                if (obj == null) return null; // isSkip()에 의한 명시적 드롭
                enrich(obj);                  // 정규화 + 이벤트 분류
                return obj;
            }
        }
        // 어떤 패턴에도 매칭되지 않은 경우 → 원본을 그대로 fallback 처리
        return fallback(log);
    }

    /**
     * 여러 줄로 분산된 로그를 논리적 단위로 병합한다.
     *
     * 스택트레이스, 멀티라인 메시지 등은 여러 줄에 걸쳐 있으나
     * 하나의 로그 이벤트이므로, 새 로그 시작 패턴이 나올 때까지 이전 줄을 이어붙인다.
     *
     * 예:
     *   2024-01-01 ERROR - DB connection failed    ← 새 로그 시작
     *   java.sql.SQLException: Timeout             ← 병합 대상
     *       at com.example.DB.connect(DB.java:42)  ← 병합 대상
     *   2024-01-01 INFO - Server started           ← 새 로그 시작 → 이전 것 완성
     *
     * @param lines 파일에서 읽은 원본 라인 목록
     * @return 병합된 로그 문자열 목록
     */
    private List<String> mergeLogs(List<String> lines) {
        List<String> result   = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (isNewLogStart(line)) {
                // 새 로그 시작이 감지되면, 이전까지 누적된 내용을 완성된 로그로 저장
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                    current.setLength(0);
                }
            }
            // 현재 줄을 누적 버퍼에 추가 (줄 간 공백으로 구분)
            current.append(line).append(" ");
        }

        // 마지막 로그 블록 처리
        if (current.length() > 0) result.add(current.toString().trim());

        return result;
    }

    /**
     * 해당 줄이 새로운 로그의 시작인지 판별한다.
     *
     * 지원하는 새 로그 시작 패턴:
     *   - 날짜/시각으로 시작 (YYYY-MM-DD 또는 YYYY-MM-DDThh:mm:ss)
     *   - JSON 객체로 시작 ({...)
     *   - Node.js 레벨 접두어 (info:, error: 등)
     *   - HTTP 접근 로그 형식 (IP - - [날짜] ...)
     *   - Key=Value 형식
     *
     * @param line 검사할 줄
     * @return 새 로그 시작이면 true
     */
    private boolean isNewLogStart(String line) {
        return line.matches("^\\d{4}-\\d{2}-\\d{2}[T ].*")            // 타임스탬프 시작
            || line.matches("^\\{.*")                                   // JSON 시작
            || line.matches("(?i)^(info|error|warn|debug|verbose):.*") // Node.js 레벨
            || line.matches("^\\S+\\s+\\S+\\s+\\S+\\s+\\[.*")         // HTTP 접근 로그
            || line.matches("^\\w+=.*");                                // Key=Value
    }

    /**
     * 해당 로그가 분석 불필요한 노이즈인지 판별한다.
     * NOISE_KEYWORDS 중 하나라도 포함되면 노이즈로 간주한다.
     * 대소문자 구분 없이 검사한다.
     *
     * @param log 검사할 로그 문자열
     * @return 노이즈이면 true
     */
    private boolean isNoise(String log) {
        String lower = log.toLowerCase();
        return NOISE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 파싱된 JSONObject에 정규화(normalize) 및 이벤트 분류(event classification)를 수행한다.
     *
     * 정규화 과정 (토큰 치환 순서가 중요: 구체적인 패턴을 먼저 처리):
     *   UUID     → <UUID>          : 분산 트레이싱 ID 등
     *   IP(:포트) → <IP>           : IPv4 주소
     *   URL      → <URL>           : http/https URL
     *   EMAIL    → <EMAIL>         : 이메일 주소
     *   10자리+ 숫자 → <ID>        : 사용자 ID, 주문번호 등 긴 식별자
     *   5~9자리 숫자 → <NUM>       : 포트, 에러코드 등 일반 숫자
     *   user=값  → user=<USER>     : 사용자명
     *   password → <MASKED>        : 비밀번호 마스킹
     *   token    → <MASKED>        : 토큰 마스킹
     *   /경로/   → <PATH>          : 파일/URL 경로
     *
     * @param obj 파싱된 JSON 객체 (message 필드 필수), in-place 로 수정됨
     */
    private void enrich(JSONObject obj) {

        String raw = obj.optString("message", "").toLowerCase().trim();

        // Exception 메시지는 단순화하여 템플릿 추출 효율을 높임
        // 예: "java.lang.NullPointerException: Cannot invoke..." → "exception occurred: nullpointerexception"
        if (raw.contains("exception") || raw.contains("caused by")) {
            raw = "exception occurred: " + extractExceptionType(raw);
        }

        // 토큰 치환: 가변값을 의미 태그로 대체하여 로그 구조를 표준화
        // 치환 순서: UUID > IP > URL > EMAIL > ID > NUM > USER > MASKED > PATH
        // (더 구체적인 패턴을 먼저 처리해야 오탐 없이 정확히 치환됨)
        raw = raw.replaceAll(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<UUID>");
        raw = raw.replaceAll("\\b\\d+\\.\\d+\\.\\d+\\.\\d+(?::\\d+)?\\b", "<IP>");   // IP 먼저 처리 안 하면 <NUM>에 흡수됨
        raw = raw.replaceAll("https?://[^\\s\"']+", "<URL>");
        raw = raw.replaceAll("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b", "<EMAIL>");
        raw = raw.replaceAll("\\b\\d{10,}\\b", "<ID>");                               // 10자리 이상 → 트랜잭션ID 등
        raw = raw.replaceAll("\\b\\d{5,9}\\b", "<NUM>");                              // 5~9자리 → 포트, 에러코드 등
        raw = raw.replaceAll("user(?:name)?=\\w+", "user=<USER>");
        raw = raw.replaceAll("password=\\S+", "password=<MASKED>");                   // 비밀번호 노출 방지
        raw = raw.replaceAll("token=\\S+", "token=<MASKED>");                         // 토큰 노출 방지
        raw = raw.replaceAll("/(?:[\\w.%-]+/){2,}[\\w.%-]*", "<PATH>");               // 2단계 이상의 경로
        raw = raw.replaceAll("\\s+", " ").trim();                                      // 연속 공백 정리

        obj.put("normalized", raw);

        // ── 이벤트 분류 ────────────────────────────────────────────────────
        // 우선순위: 구체적(도메인) 이벤트 → 기술적 이벤트 → 일반 성공/실패 → 기타
        String event;

        if (raw.contains("login") && matchesAny(raw, "success", "succeed", "authenticated")) {
            event = "login_success";

        } else if (raw.contains("login") && matchesAny(raw, "fail", "error", "invalid", "denied")) {
            event = "login_failed";

        } else if (raw.contains("logout")) {
            event = "logout";

        } else if (matchesAny(raw, "unauthorized", "forbidden", "401", "403")) {
            event = "auth_error";

        } else if (raw.contains("order") && matchesAny(raw, "fail", "error", "cancel")) {
            event = "order_fail";

        } else if (raw.contains("order") && matchesAny(raw, "success", "complete", "placed")) {
            event = "order_success";

        } else if (matchesAny(raw, "payment", "billing", "charge", "refund")) {
            event = "payment";

        } else if (matchesAny(raw, "database", "db ", "sql", "jdbc", "query")) {
            event = "db_error";

        } else if (raw.contains("timeout")) {
            event = "timeout";

        } else if (matchesAny(raw, "connection refused", "connection error", "connect failed")) {
            event = "connection_error";

        } else if (matchesAny(raw, "exception occurred", "caused by", "stack trace")) {
            event = "exception";

        } else if (raw.startsWith("http ")) {
            // HTTP 이벤트는 상태 코드 기준으로 세분화
            event = classifyHttpEvent(obj.optString("http_status", ""));

        } else if (matchesAny(raw, "start", "started", "initializ", "boot")) {
            event = "startup";

        } else if (matchesAny(raw, "stop", "stopped", "shutdown", "destroy")) {
            event = "shutdown";

        } else if (raw.contains("fail") || raw.contains("error")) {
            event = "fail";

        } else if (raw.contains("success") || raw.contains("succeed") || raw.contains("complete")) {
            event = "success";

        } else {
            event = "etc";
        }

        obj.put("event", event);
    }

    /**
     * 어떤 패턴에도 매칭되지 않은 로그를 처리하는 최후 수단(fallback).
     * 원본 로그 전체를 message 로 설정하고 enrich()를 통해 정규화 및 이벤트 분류를 시도한다.
     *
     * @param log 패턴 매칭에 실패한 원본 로그 문자열
     * @return 정규화된 JSONObject
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
     * 해당 로그 레벨을 분석 대상에서 제외해야 하는지 판별한다.
     * DEBUG, TRACE, VERBOSE 는 개발 디버깅용으로 AI 분석에 노이즈가 될 수 있으므로 제외한다.
     *
     * @param level 로그 레벨 문자열 (대소문자 무관)
     * @return 제외 대상이면 true
     */
    private boolean isSkip(String level) {
        String l = level.toUpperCase();
        return l.equals("DEBUG") || l.equals("TRACE") || l.equals("VERBOSE");
    }

    /**
     * text가 keywords 중 하나라도 포함하는지 검사한다.
     *
     * @param text     검사할 문자열
     * @param keywords 검사할 키워드 목록 (가변 인수)
     * @return 하나라도 포함되면 true
     */
    private boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /**
     * HTTP 상태 코드를 기반으로 이벤트 유형을 세분화한다.
     *
     * 2xx → http_success      (정상 응답)
     * 3xx → http_redirect     (리다이렉트)
     * 4xx → http_client_error (클라이언트 오류: 404 Not Found, 403 Forbidden 등)
     * 5xx → http_server_error (서버 오류: 500 Internal Server Error 등)
     *
     * @param status HTTP 상태 코드 문자열 (예: "200", "404", "500")
     * @return 이벤트 유형 문자열
     */
    private String classifyHttpEvent(String status) {
        if (status.startsWith("2")) return "http_success";
        if (status.startsWith("3")) return "http_redirect";
        if (status.startsWith("4")) return "http_client_error";
        if (status.startsWith("5")) return "http_server_error";
        return "http";
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
    private String extractExceptionType(String msg) {
        Pattern p = Pattern.compile(
            "([\\w.]+exception|[\\w.]+error)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(msg);
        if (m.find()) {
            String full    = m.group(1);
            int    lastDot = full.lastIndexOf('.');
            // 패키지 경로 제거: "java.lang.NullPointerException" → "nullpointerexception"
            return lastDot >= 0
                ? full.substring(lastDot + 1).toLowerCase()
                : full.toLowerCase();
        }
        return "unknown";
    }
}