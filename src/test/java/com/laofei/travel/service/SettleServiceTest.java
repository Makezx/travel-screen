package com.laofei.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laofei.travel.model.TripItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SettleService} 单元测试（JUnit 5，纯内存计算，不启动 Spring 容器 / 不连数据库）。
 *
 * <p>所有金额断言都用 {@code compareTo} 比较（BigDecimal 的 equals 对 scale 敏感，
 * 金额语义上 50 与 50.00 必须视为相等），并对每一处关键数值给出精确期望。
 */
class SettleServiceTest {

    private SettleService settleService;

    @BeforeEach
    void setUp() {
        settleService = new SettleService(new ObjectMapper());
    }

    /* ---------------- 用例 1：两人 AA，一笔转账 ---------------- */

    @Test
    @DisplayName("两人 AA：A 垫付 100 元两人分摊 → A +50 / B -50，恰好一笔「B 给 A 50」")
    void twoPersonSplit_producesSingleTransfer() {
        TripItem dinner = item("100.00", "A", "[\"A\",\"B\"]");

        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B"), List.of(dinner));

        assertMoney("100.00", total(r));
        assertEquals(2, people(r).size());

        Map<String, Object> a = person(r, "A");
        assertMoney("100.00", a.get("paid"));
        assertMoney("50.00", a.get("share"));
        assertMoney("50.00", a.get("balance"));

        Map<String, Object> b = person(r, "B");
        assertMoney("0.00", b.get("paid"));
        assertMoney("50.00", b.get("share"));
        assertMoney("-50.00", b.get("balance"));

        List<Map<String, Object>> transfers = transfers(r);
        assertEquals(1, transfers.size(), "两人 AA 应只需一笔转账");
        assertEquals("B", transfers.get(0).get("from"));
        assertEquals("A", transfers.get(0).get("to"));
        assertMoney("50.00", transfers.get(0).get("amount"));

        // 输出 key 契约（前端 showAA() 依赖）
        assertEquals(List.of("name", "paid", "share", "balance"), new ArrayList<>(a.keySet()));
        assertEquals(List.of("from", "to", "amount"), new ArrayList<>(transfers.get(0).keySet()));
        assertEquals(List.of("people", "transfers", "total"), new ArrayList<>(r.keySet()));
    }

    /* ---------------- 用例 2：100 元 3 人除不尽 ---------------- */

    @Test
    @DisplayName("100 元 3 人分摊（10000 分 ÷ 3）：前 1 人多得 1 分，且各人 share 之和严格等于总额")
    void unevenSplit_remainderGoesToFirstPeople_andSharesSumToTotal() {
        TripItem item = item("100.00", "A", "[]");

        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B", "C"), List.of(item));

        assertMoney("33.34", person(r, "A").get("share"));
        assertMoney("33.33", person(r, "B").get("share"));
        assertMoney("33.33", person(r, "C").get("share"));

        // 最关键断言：分摊额之和必须严格等于总额，不能因为整除丢分
        assertMoney("100.00", sumShares(r));
        assertMoney("100.00", total(r));

        assertMoney("66.66", person(r, "A").get("balance"));
        assertMoney("-33.33", person(r, "B").get("balance"));
        assertMoney("-33.33", person(r, "C").get("balance"));
        assertMoney("0.00", sumBalances(r));

        List<Map<String, Object>> transfers = transfers(r);
        assertEquals(2, transfers.size());
        assertEquals("B", transfers.get(0).get("from"));
        assertEquals("A", transfers.get(0).get("to"));
        assertMoney("33.33", transfers.get(0).get("amount"));
        assertEquals("C", transfers.get(1).get("from"));
        assertEquals("A", transfers.get(1).get("to"));
        assertMoney("33.33", transfers.get(1).get("amount"));
    }

    @Test
    @DisplayName("余数分给名单靠前的人：0.01 元 3 人分摊 → 第一人 0.01、其余为 0，总和仍等于 0.01")
    void oneCentSplitAmongThree_givesEntireCentToFirstPerson() {
        TripItem item = item("0.01", "A", "[]");

        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B", "C"), List.of(item));

        assertMoney("0.01", person(r, "A").get("share"));
        assertMoney("0.00", person(r, "B").get("share"));
        assertMoney("0.00", person(r, "C").get("share"));
        assertMoney("0.01", sumShares(r));
    }

    @Test
    @DisplayName("多笔混合金额：各人 share 之和 == total，且所有 balance 之和 == 0（不丢分、不凭空生分）")
    void multipleItems_sharesSumToTotal_andBalancesSumToZero() {
        List<TripItem> items = List.of(
                item("33.33", "A", "[]"),                       // 3 人均摊 → 11.11 / 11.11 / 11.11
                item("0.07", "B", "[\"A\",\"B\",\"C\"]"),        // 7 分 ÷ 3 → 3 / 2 / 2 分
                item("199.99", "C", "[\"B\",\"C\"]"),            // 19999 分 ÷ 2 → 10000 / 9999 分
                item("12.10", "A", "[\"A\"]"));                  // 单人独占

        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B", "C"), items);

        assertMoney("245.49", total(r));
        assertMoney("245.49", sumShares(r));
        assertMoney("0.00", sumBalances(r));

        // 转账总额必须恰好等于债务人应付总额（债权/债务守恒）
        assertEquals(0, positiveBalanceSum(r).compareTo(transferredSum(r)),
                "转账总额应等于债权总额");
    }

    /* ---------------- 用例 3：单笔指定 participants 为子集 ---------------- */

    @Test
    @DisplayName("4 人团队中某笔只指定 2 人参与 → 只有这 2 人承担该笔，其余人 share 为 0")
    void subsetParticipants_onlyListedPeopleBearTheCost() {
        List<TripItem> items = List.of(
                item("120.00", "A", "[\"A\",\"B\"]"),   // 只有 A、B 分摊 → 各 60
                item("40.00", "C", "[]"));              // 空 participants → 全体 4 人均摊 → 各 10

        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B", "C", "D"), items);

        assertMoney("160.00", total(r));
        assertMoney("160.00", sumShares(r));

        assertMoney("70.00", person(r, "A").get("share"));
        assertMoney("70.00", person(r, "B").get("share"));
        assertMoney("10.00", person(r, "C").get("share"));
        assertMoney("10.00", person(r, "D").get("share"));

        assertMoney("50.00", person(r, "A").get("balance"));
        assertMoney("-70.00", person(r, "B").get("balance"));
        assertMoney("30.00", person(r, "C").get("balance"));
        assertMoney("-10.00", person(r, "D").get("balance"));

        List<Map<String, Object>> transfers = transfers(r);
        assertEquals(3, transfers.size(), "两指针贪心应给出 3 笔最小转账");
        assertEquals("B", transfers.get(0).get("from"));
        assertEquals("A", transfers.get(0).get("to"));
        assertMoney("50.00", transfers.get(0).get("amount"));
        assertEquals("B", transfers.get(1).get("from"));
        assertEquals("C", transfers.get(1).get("to"));
        assertMoney("20.00", transfers.get(1).get("amount"));
        assertEquals("D", transfers.get(2).get("from"));
        assertEquals("C", transfers.get(2).get("to"));
        assertMoney("10.00", transfers.get(2).get("amount"));
    }

    /* ---------------- 用例 4：付款人不在 people 名单内 ---------------- */

    @Test
    @DisplayName("付款人不在名单内 → 仍计入 paid 并出现在输出末尾，转账方向正确（他人付给该付款人）")
    void payerOutsidePeopleList_stillCountedAndAppearsInOutput() {
        TripItem item = item("60.00", "X", "[]");

        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B"), List.of(item));

        List<Map<String, Object>> people = people(r);
        assertEquals(3, people.size(), "名单外付款人也必须出现在输出里");
        assertEquals(List.of("A", "B", "X"), people.stream().map(x -> x.get("name")).toList(),
                "输出顺序：先名单顺序，再名单外付款人");

        assertMoney("60.00", person(r, "X").get("paid"));
        assertMoney("0.00", person(r, "X").get("share"));
        assertMoney("60.00", person(r, "X").get("balance"));
        assertMoney("-30.00", person(r, "A").get("balance"));
        assertMoney("-30.00", person(r, "B").get("balance"));
        assertMoney("60.00", sumShares(r));

        List<Map<String, Object>> transfers = transfers(r);
        assertEquals(2, transfers.size());
        for (Map<String, Object> tr : transfers) {
            assertEquals("X", tr.get("to"), "钱款方向必须指向实际垫付人 X");
        }
        assertEquals("A", transfers.get(0).get("from"));
        assertMoney("30.00", transfers.get(0).get("amount"));
        assertEquals("B", transfers.get(1).get("from"));
        assertMoney("30.00", transfers.get(1).get("amount"));
    }

    /* ---------------- 用例 5：已平账 ---------------- */

    @Test
    @DisplayName("互相抵消已平账 → 人人 balance = 0，transfers 为空列表")
    void fullySettledUp_producesNoTransfers() {
        List<TripItem> items = List.of(
                item("100.00", "A", "[]"),
                item("100.00", "B", "[]"));

        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B"), items);

        assertMoney("200.00", total(r));
        assertMoney("100.00", person(r, "A").get("share"));
        assertMoney("100.00", person(r, "B").get("share"));
        assertMoney("0.00", person(r, "A").get("balance"));
        assertMoney("0.00", person(r, "B").get("balance"));
        assertTrue(transfers(r).isEmpty(), "已平账不应产生任何转账");
        assertMoney("0.00", sumBalances(r));
    }

    @Test
    @DisplayName("无明细 → 名单人人 0 元，transfers 为空，total 为 0")
    void noItems_producesZeroResult() {
        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B"), List.of());

        assertEquals(2, people(r).size());
        assertMoney("0.00", total(r));
        assertMoney("0.00", sumShares(r));
        assertMoney("0.00", person(r, "A").get("balance"));
        assertTrue(transfers(r).isEmpty());

        // 入参为 null 也必须安全（空名单 + null 明细不抛异常）
        Map<String, Object> nullCase = settleService.calculate(null, null);
        assertNotNull(nullCase);
        assertTrue(people(nullCase).isEmpty());
        assertMoney("0.00", total(nullCase));
        assertTrue(transfers(nullCase).isEmpty());
    }

    /* ---------------- 用例 6：金额为 0 / 负数被跳过 ---------------- */

    @Test
    @DisplayName("金额为 0 或负数的明细被跳过：不计入 total、不产生 paid、不影响分摊")
    void zeroAndNegativeAmounts_areSkipped() {
        List<TripItem> items = List.of(
                item("0.00", "A", "[]"),
                item("-30.00", "A", "[]"),
                item("-0.01", "B", "[]"),
                item("100.00", "A", "[]"));

        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B"), items);

        assertMoney("100.00", total(r), "总额只能包含唯一一笔正数明细");
        assertMoney("100.00", person(r, "A").get("paid"));
        assertMoney("0.00", person(r, "B").get("paid"), "负数明细不得给 B 记 paid");
        assertMoney("50.00", person(r, "A").get("share"));
        assertMoney("50.00", person(r, "B").get("share"));
        assertMoney("100.00", sumShares(r));
        assertMoney("0.00", sumBalances(r));

        List<Map<String, Object>> transfers = transfers(r);
        assertEquals(1, transfers.size());
        assertEquals("B", transfers.get(0).get("from"));
        assertEquals("A", transfers.get(0).get("to"));
        assertMoney("50.00", transfers.get(0).get("amount"));
    }

    @Test
    @DisplayName("金额为 null 的明细同样被跳过，总额只统计有效正数")
    void nullAmount_isSkipped() {
        TripItem nullAmt = new TripItem(1L, "2024.01.01", "吃", "无金额", null, "A", "");

        Map<String, Object> r = settleService.calculate(Arrays.asList("A"), List.of(nullAmt));

        assertMoney("0.00", total(r));
        assertMoney("0.00", person(r, "A").get("paid"));
        assertTrue(transfers(r).isEmpty());
    }

    /* ---------------- 用例 7：participants 非法 / 空名单回退 ---------------- */

    @Test
    @DisplayName("participants 为非法 JSON 时回退为全体名单；名单为空时回退为 [付款人]")
    void malformedParticipants_fallsBackToAllPeople_thenToPayer() {
        TripItem broken = item("30.00", "A", "not-a-json");

        Map<String, Object> withPeople = settleService.calculate(Arrays.asList("A", "B"), List.of(broken));
        assertMoney("15.00", person(withPeople, "A").get("share"));
        assertMoney("15.00", person(withPeople, "B").get("share"));
        assertMoney("30.00", sumShares(withPeople));

        Map<String, Object> withoutPeople = settleService.calculate(List.of(), List.of(broken));
        assertEquals(1, people(withoutPeople).size(), "名单为空时应只回退出付款人 A");
        assertMoney("30.00", person(withoutPeople, "A").get("paid"));
        assertMoney("30.00", person(withoutPeople, "A").get("share"));
        assertMoney("0.00", person(withoutPeople, "A").get("balance"), "自己付自己摊 → 净额为 0");
        assertTrue(transfers(withoutPeople).isEmpty());
    }

    /* ---------------- 全局不变量：转账执行后所有人归零 ---------------- */

    @Test
    @DisplayName("不变量：按 transfers 依次转账后，所有人 balance 归零，且转账总额 == 债务总额")
    void transfersInvariant_settlesEveryoneToZero() {
        List<TripItem> items = List.of(
                item("77.77", "A", "[\"A\",\"B\",\"C\"]"),
                item("13.13", "B", "[\"B\",\"C\"]"),
                item("0.02", "C", "[\"A\",\"B\",\"C\"]"),
                item("500.00", "D", "[\"A\",\"D\"]"));

        Map<String, Object> r = settleService.calculate(Arrays.asList("A", "B", "C", "D"), items);

        // 每笔分摊之和严格等于总额
        assertMoney("590.92", total(r));
        assertMoney("590.92", sumShares(r));
        assertMoney("0.00", sumBalances(r));

        // 模拟执行转账：从 from 扣、给 to 加，最终人人 balance 归零
        Map<String, BigDecimal> delta = new LinkedHashMap<>();
        for (Map<String, Object> p : people(r)) {
            delta.put((String) p.get("name"), money(p.get("balance")));
        }
        BigDecimal moved = BigDecimal.ZERO;
        for (Map<String, Object> tr : transfers(r)) {
            BigDecimal amt = money(tr.get("amount"));
            assertTrue(amt.compareTo(BigDecimal.ZERO) > 0, "转账金额必须为正");
            delta.merge((String) tr.get("from"), amt, BigDecimal::add);
            delta.merge((String) tr.get("to"), amt.negate(), BigDecimal::add);
            moved = moved.add(amt);
        }
        for (Map.Entry<String, BigDecimal> e : delta.entrySet()) {
            assertMoney("0.00", e.getValue(), "转账执行后「" + e.getKey() + "」应恰好归零");
        }

        // 转账总额 = 债权人应收总额（债权/债务守恒，且不重复转移）
        assertEquals(0, positiveBalanceSum(r).compareTo(transferredSum(r)),
                "转账总额应等于债权总额");
        assertEquals(0, moved.compareTo(transferredSum(r)));
    }

    /* ---------------- 辅助方法 ---------------- */

    /** 构造一笔明细；participantsJson 传 "[]" 表示由行程全体名单分摊。 */
    private static TripItem item(String amt, String payer, String participantsJson) {
        TripItem it = new TripItem(1L, "2024.08.06", "吃", "测试明细", new BigDecimal(amt), payer, "");
        it.setParticipantsJson(participantsJson);
        return it;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> people(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("people");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> transfers(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("transfers");
    }

    private static Map<String, Object> person(Map<String, Object> result, String name) {
        return people(result).stream()
                .filter(p -> name.equals(p.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("输出中找不到人员：" + name));
    }

    private static BigDecimal total(Map<String, Object> result) {
        return money(result.get("total"));
    }

    private static BigDecimal sumShares(Map<String, Object> result) {
        BigDecimal s = BigDecimal.ZERO;
        for (Map<String, Object> p : people(result)) s = s.add(money(p.get("share")));
        return s;
    }

    private static BigDecimal sumBalances(Map<String, Object> result) {
        BigDecimal s = BigDecimal.ZERO;
        for (Map<String, Object> p : people(result)) s = s.add(money(p.get("balance")));
        return s;
    }

    /** 所有债权人（balance &gt; 0）应收总额。 */
    private static BigDecimal positiveBalanceSum(Map<String, Object> result) {
        BigDecimal s = BigDecimal.ZERO;
        for (Map<String, Object> p : people(result)) {
            BigDecimal b = money(p.get("balance"));
            if (b.signum() > 0) s = s.add(b);
        }
        return s;
    }

    /** 所有转账金额之和。 */
    private static BigDecimal transferredSum(Map<String, Object> result) {
        BigDecimal s = BigDecimal.ZERO;
        for (Map<String, Object> tr : transfers(result)) s = s.add(money(tr.get("amount")));
        return s;
    }

    private static BigDecimal money(Object v) {
        assertNotNull(v, "金额字段不应为 null");
        return v instanceof BigDecimal bd ? bd : new BigDecimal(v.toString());
    }

    private static void assertMoney(String expected, Object actual) {
        assertMoney(expected, actual, null);
    }

    /** 以数值语义比较金额（忽略 BigDecimal 的 scale 差异）。 */
    private static void assertMoney(String expected, Object actual, String message) {
        BigDecimal exp = new BigDecimal(expected);
        BigDecimal act = money(actual);
        String prefix = message == null ? "" : message + " —— ";
        assertEquals(0, exp.compareTo(act),
                prefix + "期望金额 " + exp.toPlainString() + "，实际 " + act.toPlainString());
    }
}
