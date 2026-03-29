package org.openjfx.hellofx;

import java.util.*;

public class LogPatternExtractor {

    private List<List<String>> templates = new ArrayList<>();
    private Map<String, Integer> templateIds = new HashMap<>();
    private int idCounter = 1;
    
    // 간단한 토큰 기반 패턴 추출기
    public String addLog(String log) {

        List<String> tokens = Arrays.asList(log.split("\\s+"));

        for (List<String> t : templates) {

            if (similar(t, tokens)) {

                List<String> merged = merge(t, tokens);

                templates.remove(t);
                templates.add(merged);

                return String.join(" ", merged);
            }
        }

        templates.add(tokens);
        return String.join(" ", tokens);
    }

    // 템플릿 ID 부여
    public int getTemplateId(String template) {
        templateIds.putIfAbsent(template, idCounter++);
        return templateIds.get(template);
    }

    // 유사도 70% 이상이면 같은 패턴으로 간주
    private boolean similar(List<String> t1, List<String> t2) {

        if (t1.size() != t2.size()) return false;

        int same = 0;

        for (int i = 0; i < t1.size(); i++) {
            if (t1.get(i).equals(t2.get(i))) same++;
        }

        return (double) same / t1.size() >= 0.7;
    }

    // 같은 위치의 토큰이 다르면 <*>로 병합
    private List<String> merge(List<String> t1, List<String> t2) {

        List<String> result = new ArrayList<>();

        for (int i = 0; i < t1.size(); i++) {
            result.add(t1.get(i).equals(t2.get(i)) ? t1.get(i) : "<*>");
        }

        return result;
    }
}