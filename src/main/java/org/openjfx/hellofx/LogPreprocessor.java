package org.openjfx.hellofx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.*;

public class LogPreprocessor {

    // =========================
    // 패턴 구조
    // =========================
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

    private List<LogPattern> patterns = new ArrayList<>();
    private LogPatternExtractor extractor = new LogPatternExtractor();

    // =========================
    // ⭐ 중복 제거 관련
    // =========================
    private Set<String> seenLogs = new HashSet<>();
    private Map<String, Integer> templateCount = new HashMap<>();

    private static final int MAX_TEMPLATE_REPEAT = 50;
    private static final int MAX_BURST = 5;

    private String lastLog = "";
    private int burstCount = 0;

    // =========================
    // 생성자
    // =========================
    public LogPreprocessor() {

        // 1️ JSON
        patterns.add(new LogPattern(
            Pattern.compile("^\\{.*\\}$"),
            (m, log) -> {
                try {
                    JSONObject json = new JSONObject(log);
                    String level = json.optString("level");

                    if (isSkip(level)) return null;

                    JSONObject obj = new JSONObject();
                    obj.put("message", json.optString("message", log));
                    return obj;
                } catch (Exception e) {
                    return null;
                }
            }
        ));

        // 2️ Spring Logback
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2} .*?)\\s+" +
                "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+" +
                "\\[(.*?)\\]\\s+([\\w\\.$]+)\\s+:\\s+(.*)$"
            ),
            (m, log) -> {
                if (isSkip(m.group(2))) return null;

                JSONObject obj = new JSONObject();
                obj.put("message", m.group(5));
                return obj;
            }
        ));

        // 3️ Simple Logback
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2} .*?)\\s+\\[(.*?)\\]\\s+" +
                "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+([\\w\\.$]+)\\s+-\\s+(.*)$"
            ),
            (m, log) -> {
                if (isSkip(m.group(3))) return null;

                JSONObject obj = new JSONObject();
                obj.put("message", m.group(5));
                return obj;
            }
        ));

        // 4️ Python
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2} .*?) - (INFO|ERROR|WARN|DEBUG) - (.*)$"
            ),
            (m, log) -> {
                if (isSkip(m.group(2))) return null;

                JSONObject obj = new JSONObject();
                obj.put("message", m.group(3));
                return obj;
            }
        ));

        // 5️ Node
        patterns.add(new LogPattern(
            Pattern.compile("^(info|error|warn|debug):\\s+(.*)$"),
            (m, log) -> {
                if (isSkip(m.group(1))) return null;

                JSONObject obj = new JSONObject();
                obj.put("message", m.group(2));
                return obj;
            }
        ));

        // 6️ HTTP
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\S+) \\S+ \\S+ \\[(.*?)\\] \"(GET|POST|PUT|DELETE).*?\" (\\d{3}) (\\d+)"
            ),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                obj.put("message", "http " + m.group(3) + " " + m.group(4));
                return obj;
            }
        ));

        // 7️ Exception
        patterns.add(new LogPattern(
            Pattern.compile(".*(Exception|Error|Caused by).*"),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                obj.put("message", "exception occurred");
                return obj;
            }
        ));

        // 8️ Key=Value
        patterns.add(new LogPattern(
            Pattern.compile("^(\\w+=.+)$"),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                obj.put("message", log);
                return obj;
            }
        ));
    }

    // =========================
    // 실행
    // =========================
    public JSONArray processFile(String filePath) {

        JSONArray result = new JSONArray();
        int seq = 1;

        try {
            List<String> merged = mergeLogs(Files.readAllLines(Paths.get(filePath)));

            for (String log : merged) {

                if (log.trim().isEmpty()) continue;

                //  노이즈 제거
                if (isNoise(log)) continue;

                JSONObject parsed = parse(log);
                if (parsed == null) continue;

                String normalized = parsed.getString("normalized");

                // =========================
                // 1️ 완전 중복 제거
                // =========================
                if (seenLogs.contains(normalized)) continue;
                seenLogs.add(normalized);

                // =========================
                // 2️ burst 제거
                // =========================
                if (normalized.equals(lastLog)) {
                    burstCount++;
                    if (burstCount > MAX_BURST) continue;
                } else {
                    lastLog = normalized;
                    burstCount = 0;
                }

                // =========================
                // 3️ 의미 없는 로그 제거
                // =========================
                if (normalized.length() < 5) continue;
                if (normalized.equals("ok")) continue;

                String event = parsed.getString("event");

                String template = extractor.addLog(normalized);

                // =========================
                // 4️ 템플릿 반복 제한
                // =========================
                int count = templateCount.getOrDefault(template, 0);
                if (count > MAX_TEMPLATE_REPEAT) continue;
                templateCount.put(template, count + 1);

                int templateId = extractor.getTemplateId(template);

                JSONObject ai = new JSONObject();
                ai.put("sequence_id", seq++);
                ai.put("event", event);
                ai.put("template", template);
                ai.put("template_id", templateId);
                ai.put("tokens", Arrays.asList(template.split("\\s+")));

                result.put(ai);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }

    // =========================
    // 파싱
    // =========================
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

    // =========================
    // 멀티라인 병합
    // =========================
    private List<String> mergeLogs(List<String> lines) {

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : lines) {

            if (isNewLogStart(line)) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            }

            current.append(line).append(" ");
        }

        if (current.length() > 0) result.add(current.toString());

        return result;
    }

    private boolean isNewLogStart(String line) {
        return line.matches("^\\d{4}-\\d{2}-\\d{2}.*")
            || line.matches("^\\{.*\\}$")
            || line.matches("^(info|error|warn|debug):.*")
            || line.matches("^\\S+ \\S+ \\S+ \\[.*\\].*");
    }

    // =========================
    //  노이즈 제거
    // =========================
    private boolean isNoise(String log) {

        String lower = log.toLowerCase();

        return lower.contains("healthcheck")
            || lower.contains("heartbeat")
            || lower.contains("metrics")
            || lower.contains("favicon.ico")
            || lower.contains("connection reset");
    }

    // =========================
    // 전처리
    // =========================
    private void enrich(JSONObject obj) {

        String msg = obj.getString("message").toLowerCase();

        if (msg.contains("exception")) msg = "exception occurred";

        msg = msg.replaceAll("\\b\\d{10,}\\b", "<ID>");
        msg = msg.replaceAll("\\b\\d{5,}\\b", "<NUM>");
        msg = msg.replaceAll("\\b\\d+\\.\\d+\\.\\d+\\.\\d+\\b", "<IP>");
        msg = msg.replaceAll("user=\\w+", "user=<USER>");
        msg = msg.replaceAll("\\s+", " ").trim();

        obj.put("normalized", msg);

        // 이벤트 분류
        if (msg.contains("login") && msg.contains("success")) obj.put("event", "login_success");
        else if (msg.contains("login") && msg.contains("fail")) obj.put("event", "login_failed");
        else if (msg.contains("order") && msg.contains("fail")) obj.put("event", "order_fail");
        else if (msg.contains("database")) obj.put("event", "db_error");
        else if (msg.contains("exception")) obj.put("event", "exception");
        else if (msg.contains("http")) obj.put("event", "http");
        else if (msg.contains("fail")) obj.put("event", "fail");
        else if (msg.contains("success")) obj.put("event", "success");
        else obj.put("event", "etc");
    }

    private JSONObject fallback(String log) {
        JSONObject obj = new JSONObject();
        obj.put("message", log);
        enrich(obj);
        return obj;
    }

    // =========================
    // 로그 레벨 필터
    // =========================
    private boolean isSkip(String level) {
        return "DEBUG".equalsIgnoreCase(level) || "TRACE".equalsIgnoreCase(level);
    }
}