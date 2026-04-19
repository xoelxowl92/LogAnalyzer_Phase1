package org.openjfx.hellofx;

import java.util.*;

/**
 * DuplicateFilter
 *
 * 반복/중복 로그를 제거하는 전담 필터 클래스.
 * LogPreprocessor 에서 분리하여 단일 책임 원칙(SRP)을 준수한다.
 *
 * 제거 전략 3단계:
 *
 *   [1단계] 완전 중복 제거 (Exact Dedup)
 *           정규화된 메시지가 이전에 등장한 적 있으면 제거.
 *           단, 이벤트 레벨이 ERROR/WARN 이면 빈도 정보 보존을 위해 통과시킨다.
 *           → 반복적인 INFO 로그(예: "서버 정상 동작 중")는 한 번만 기록
 *
 *   [2단계] Burst 제거 (Burst Suppression)
 *           동일 메시지가 연속으로 MAX_BURST 회 초과 시 제거.
 *           → 장애 상황에서 같은 오류가 수천 번 쏟아지는 폭주 억제
 *
 *   [3단계] 템플릿 반복 제한 (Template Throttle)
 *           가변값을 제거한 구조 패턴(템플릿)이 MAX_TEMPLATE_REPEAT 회 초과 시 제거.
 *           → AI 입력이 특정 패턴으로 편향되는 것을 방지
 *
 * 판정 결과 (FilterResult):
 *   PASS          - 통과 (출력에 포함)
 *   DROP_EXACT    - 완전 중복으로 제거
 *   DROP_BURST    - 연속 폭주로 제거
 *   DROP_THROTTLE - 템플릿 반복 한도 초과로 제거
 */
public class DuplicateFilter {

    // =========================================================================
    // 판정 결과 열거형
    // =========================================================================

    /**
     * 중복 필터 판정 결과.
     * PASS 만 출력 대상이며, DROP_* 는 각각 제거 이유를 명시한다.
     */
    public enum FilterResult {
        PASS,           // 통과
        DROP_EXACT,     // 완전 중복 (동일 메시지 재등장)
        DROP_BURST,     // Burst 폭주 (동일 메시지 연속 MAX_BURST 초과)
        DROP_THROTTLE   // 템플릿 반복 한도 초과
    }

    // =========================================================================
    // 설정 상수
    // =========================================================================

    /**
     * Burst 허용 횟수.
     * 동일 정규화 메시지가 연속으로 이 횟수를 초과하면 이후 로그는 드롭된다.
     *
     * 예) MAX_BURST = 5 → 같은 오류가 1~5번째까지는 통과, 6번째부터 드롭
     * 값을 높이면 더 많은 반복을 허용 (폭주 감지가 느려짐)
     * 값을 낮추면 더 공격적으로 억제 (빈도 정보 손실 증가)
     */
    private static final int MAX_BURST = 5;

    /**
     * 동일 템플릿(구조 패턴)의 최대 허용 등장 횟수.
     * 초과 시 동일 패턴의 로그는 더 이상 출력에 포함되지 않는다.
     *
     * 예) MAX_TEMPLATE_REPEAT = 50 → "user <USER> login failed from <IP>" 패턴이
     *     50번 등장 후에는 그 이후 동일 패턴 로그를 모두 드롭
     */
    private static final int MAX_TEMPLATE_REPEAT = 50;

    // =========================================================================
    // 상태 필드
    // =========================================================================

    /**
     * [1단계] 완전 중복 감지용 집합.
     * 정규화된 메시지를 키로 저장하고, 이미 등장한 적 있는지 판별한다.
     * INFO 수준의 반복 로그 제거에 사용된다.
     */
    private final Set<String> seenExact = new HashSet<>();

    /**
     * [2단계] Burst 감지용 상태.
     * lastMsg: 직전에 처리된 정규화 메시지
     * burstCount: 현재 연속 반복 횟수
     */
    private String lastMsg   = "";
    private int    burstCount = 0;

    /**
     * [3단계] 템플릿별 등장 횟수 카운터.
     * key: 템플릿 문자열, value: 현재까지 등장 횟수
     */
    private final Map<String, Integer> templateCount = new HashMap<>();

    // =========================================================================
    // 핵심 메서드
    // =========================================================================

    /**
     * 주어진 로그의 중복 여부를 판정한다.
     *
     * 3단계를 순서대로 검사하며, 첫 번째로 걸리는 조건에서 드롭한다.
     * PASS 가 반환된 경우에만 출력 대상으로 처리한다.
     *
     * @param normalized 정규화된 로그 메시지 (가변값 마스킹 완료 상태)
     * @param template   Drain 알고리즘이 추출한 구조 패턴
     * @param level      로그 레벨 (ERROR/WARN 은 완전 중복 제거 면제)
     * @return FilterResult (PASS 또는 DROP_*)
     */
    public FilterResult check(String normalized, String template, String level) {

        // ── [1단계] 완전 중복 제거 ────────────────────────────────────────
        // ERROR/WARN 은 빈도 정보가 중요하므로 완전 중복 제거에서 면제한다.
        // 예) "DB connection failed" 가 ERROR 로 100번 나오면 → Burst/Template 단계에서만 억제
        boolean isCritical = "ERROR".equalsIgnoreCase(level) || "WARN".equalsIgnoreCase(level);
        if (!isCritical) {
            if (seenExact.contains(normalized)) {
                return FilterResult.DROP_EXACT;
            }
            seenExact.add(normalized);
        }

        // ── [2단계] Burst 억제 ────────────────────────────────────────────
        // 동일 메시지가 연속으로 MAX_BURST 를 초과하면 드롭.
        // 메시지가 바뀌면 카운터를 리셋한다.
        if (normalized.equals(lastMsg)) {
            burstCount++;
            if (burstCount > MAX_BURST) {
                return FilterResult.DROP_BURST;
            }
        } else {
            lastMsg    = normalized;
            burstCount = 1; // 새 메시지의 첫 등장이므로 1로 초기화
        }

        // ── [3단계] 템플릿 반복 제한 ──────────────────────────────────────
        // 동일 구조 패턴이 MAX_TEMPLATE_REPEAT 를 초과하면 드롭.
        // templateCount 는 PASS 된 로그만 카운트한다 (드롭된 것은 카운트하지 않음).
        int count = templateCount.getOrDefault(template, 0);
        if (count >= MAX_TEMPLATE_REPEAT) {
            return FilterResult.DROP_THROTTLE;
        }
        templateCount.put(template, count + 1);

        return FilterResult.PASS;
    }

    /**
     * 현재 템플릿의 등장 횟수를 반환한다.
     * check() 호출 후에 사용해야 최신 값이 반영된다.
     *
     * @param template 템플릿 문자열
     * @return 현재까지 해당 템플릿이 PASS 된 횟수 (1부터 시작)
     */
    public int getTemplateFrequency(String template) {
        return templateCount.getOrDefault(template, 0);
    }

    /**
     * 필터 상태를 초기화한다.
     * 새 파일 처리 시작 전 반드시 호출해야 이전 파일의 상태가 섞이지 않는다.
     */
    public void reset() {
        seenExact.clear();
        templateCount.clear();
        lastMsg    = "";
        burstCount = 0;
    }

    // =========================================================================
    // 통계 조회 (디버깅/모니터링용)
    // =========================================================================

    /**
     * 지금까지 완전 중복 없이 등록된 고유 메시지 수를 반환한다.
     * seenExact 에 저장된 항목 수이므로 INFO 로그 기준이다.
     *
     * @return 고유 메시지 수
     */
    public int getUniqueMessageCount() {
        return seenExact.size();
    }

    /**
     * 지금까지 등록된 고유 템플릿 수를 반환한다.
     *
     * @return 고유 템플릿 수
     */
    public int getUniqueTemplateCount() {
        return templateCount.size();
    }
}