package org.openjfx.hellofx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.*;

public class LogPreprocessor {

    // 패턴 + 처리 로직 같이 관리
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

    public LogPreprocessor() {

        // =========================
        // 1️⃣ Spring Logback
        // =========================
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2} .*?)\\s+" +
                "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+" +
                "\\[(.*?)\\]\\s+([\\w\\.$]+)\\s+:\\s+(.*)$"
            ),
            (m, log) -> {
                String level = m.group(2);
                if (isSkip(level)) return null;

                JSONObject obj = new JSONObject();
                obj.put("message", m.group(5));
                return obj;
            }
        ));

        // =========================
        // 2️⃣ Simple Logback
        // =========================
        patterns.add(new LogPattern(
            Pattern.compile(
                "^(\\d{4}-\\d{2}-\\d{2} .*?)\\s+\\[(.*?)\\]\\s+" +
                "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+([\\w\\.$]+)\\s+-\\s+(.*)$"
            ),
            (m, log) -> {
                String level = m.group(3);
                if (isSkip(level)) return null;

                JSONObject obj = new JSONObject();
                obj.put("message", m.group(5));
                return obj;
            }
        ));

        // =========================
        // 3️⃣ Apache / Nginx
        // =========================
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

        // =========================
        // 4️⃣ Python 로그
        // =========================
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

        // =========================
        // 5️⃣ Node (Winston)
        // =========================
        patterns.add(new LogPattern(
            Pattern.compile("^(info|error|warn|debug):\\s+(.*)$"),
            (m, log) -> {
                if (isSkip(m.group(1))) return null;

                JSONObject obj = new JSONObject();
                obj.put("message", m.group(2));
                return obj;
            }
        ));

        // =========================
        // 6️⃣ JSON 로그
        // =========================
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

        // =========================
        // 7️⃣ Key=Value 로그
        // =========================
        patterns.add(new LogPattern(
            Pattern.compile("^(\\w+=.+)$"),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                obj.put("message", log);
                return obj;
            }
        ));

        // =========================
        // 8️⃣ Exception fallback
        // =========================
        patterns.add(new LogPattern(
            Pattern.compile("^(java\\.|org\\.).*Exception.*"),
            (m, log) -> {
                JSONObject obj = new JSONObject();
                obj.put("message", "exception occurred");
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

                JSONObject parsed = parse(log);
                if (parsed == null) continue;

                String normalized = parsed.getString("normalized");
                String event = parsed.getString("event");

                String template = extractor.addLog(normalized);
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

            if (line.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {

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

    // =========================
    // 전처리
    // =========================
    private void enrich(JSONObject obj) {

        String msg = obj.getString("message").toLowerCase();

        if (msg.contains("exception")) msg = "exception occurred";

        msg = msg.replaceAll("\\d+", "<NUM>");
        msg = msg.replaceAll("user=\\w+", "user=<USER>");
        msg = msg.replaceAll("\\s+", " ").trim();

        obj.put("normalized", msg);

        // 이벤트 분류
        if (msg.contains("login success")) obj.put("event", "login_success");
        else if (msg.contains("login failed")) obj.put("event", "login_failed");
        else if (msg.contains("order fail")) obj.put("event", "order_fail");
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

    private boolean isSkip(String level) {
        return "DEBUG".equalsIgnoreCase(level) || "TRACE".equalsIgnoreCase(level);
    }
}