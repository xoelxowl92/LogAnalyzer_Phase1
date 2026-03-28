package org.openjfx.hellofx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.*;

public class LogBackPreprocessor {

    // 로그 패턴들
    private List<Pattern> patterns = new ArrayList<>();

    // 전역 패턴 추출기
    private LogPatternExtractor extractor = new LogPatternExtractor();

    public LogBackPreprocessor() {

        // Spring Logback
        patterns.add(Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+" +
            "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+" +
            "\\d+\\s+---\\s+" +
            "\\[(.*?)\\]\\s+" +
            "([\\w\\.$]+)\\s+:\\s+" +
            "(.*)$"
        ));

        // Simple Logback
        patterns.add(Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+" +
            "\\[(.*?)\\]\\s+" +
            "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+" +
            "([\\w\\.$]+)\\s+-\\s+" +
            "(.*)$"
        ));
    }

    // =========================
    // 전체 실행
    // =========================
    public JSONArray processFile(String filePath) {

        JSONArray result = new JSONArray();
        int seq = 1;

        try {
            List<String> rawLines = Files.readAllLines(Paths.get(filePath));

            // 멀티라인 병합
            List<String> lines = mergeLogs(rawLines);

            for (String line : lines) {

                if (line == null || line.trim().isEmpty()) continue;

                JSONObject log = parse(line);

                // DEBUG / TRACE 제거
                if (log == null) continue;

                String normalized = log.getString("normalized");
                String event = log.getString("event");

                // 전역 패턴 처리
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
    // 멀티라인 로그 병합
    // =========================
    private List<String> mergeLogs(List<String> lines) {

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : lines) {

            // timestamp 시작이면 새 로그
            if (line.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {

                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            }

            current.append(line).append(" ");
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }

    // =========================
    // 파싱
    // =========================
    private JSONObject parse(String log) {

        for (Pattern p : patterns) {

            Matcher m = p.matcher(log);

            if (m.find()) {

                JSONObject obj = new JSONObject();
                String level;

                if (p.pattern().contains("---")) {

                    level = m.group(2);

                    if ("DEBUG".equals(level) || "TRACE".equals(level)) return null;

                    obj.put("message", m.group(5));

                } else {

                    level = m.group(3);

                    if ("DEBUG".equals(level) || "TRACE".equals(level)) return null;

                    obj.put("message", m.group(5));
                }

                enrich(obj);
                return obj;
            }
        }

        return fallback(log);
    }

    // =========================
    // AI 전처리
    // =========================
    private void enrich(JSONObject obj) {

        String msg = obj.getString("message").toLowerCase();

        // Exception 통합
        if (msg.contains("exception")) {
            msg = "exception occurred";
        }

        // 일반 정규화
        msg = msg.replaceAll("\\d+", "<NUM>");
        msg = msg.replaceAll("user=\\w+", "user=<USER>");
        msg = msg.replaceAll("id=<NUM>", "id=<ID>");
        msg = msg.replaceAll("\\s+", " ").trim();

        obj.put("normalized", msg);

        // 이벤트 분류 강화
        if (msg.contains("login success")) obj.put("event", "login_success");
        else if (msg.contains("login failed")) obj.put("event", "login_failed");
        else if (msg.contains("order fail")) obj.put("event", "order_fail");
        else if (msg.contains("database")) obj.put("event", "db_error");
        else if (msg.contains("exception")) obj.put("event", "exception");
        else if (msg.contains("sample log")) obj.put("event", "sample_log");
        else if (msg.contains("fail")) obj.put("event", "fail");
        else if (msg.contains("success")) obj.put("event", "success");
        else obj.put("event", "etc");
    }

    // =========================
    // fallback
    // =========================
    private JSONObject fallback(String log) {

        JSONObject obj = new JSONObject();

        String msg = log.toLowerCase().replaceAll("\\d+", "<NUM>");

        obj.put("normalized", msg);

        if (msg.contains("exception")) obj.put("event", "exception");
        else obj.put("event", "etc");

        return obj;
    }
}