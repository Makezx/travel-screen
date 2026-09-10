package com.laofei.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripItem;
import com.laofei.travel.util.JsonUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 账单 AA 分摊结算服务。
 *
 * <p>把原先内联在 {@code ApiController#settle(Long)} 里的分摊算法抽成可独立测试的纯计算服务：
 * 输入「人员名单 + 明细列表」，输出「每人应付/实付/净额 + 最小转账清单」。
 * 不依赖数据库、不依赖请求上下文，因此可以脱离 Spring 容器做单元测试。
 *
 * <p><b>契约（前端 {@code admin.html#showAA()} 直接消费，字段名与顺序语义不可变）：</b>
 * <pre>
 * {
 *   "people":    [ { "name": String, "paid": BigDecimal, "share": BigDecimal, "balance": BigDecimal }, ... ],
 *   "transfers": [ { "from": String, "to": String, "amount": BigDecimal }, ... ],
 *   "total":     BigDecimal
 * }
 * </pre>
 *
 * <p><b>分摊规则：</b>
 * <ol>
 *   <li>金额 &le; 0 的明细直接跳过，不计入总额；</li>
 *   <li>付款人累加 paid；参与人默认取整笔的所有参与者，为空时回退为行程全体同行人员，
 *       仍为空时回退为 [付款人]；</li>
 *   <li>以「分」为单位整除后，余数依次分给名单靠前的 {@code rem} 个人，
 *       保证各人 share 之和<b>严格等于</b>该笔金额（不丢分、不产生小数尾差）；</li>
 *   <li>balance = paid - share，balance &gt; 0 为债权人（降序），&lt; 0 为债务人（升序），
 *       贪心两指针匹配得到最小转账清单。</li>
 * </ol>
 */
@Service
public class SettleService {

    private final ObjectMapper om;

    public SettleService(ObjectMapper om) {
        this.om = om;
    }

    /**
     * 解析 JSON 数组字符串（与控制器共用同一份实现，避免两份逻辑漂移）。
     *
     * @param s 形如 {@code ["张三","李四"]} 的字符串，允许 null / 空白 / 非法 JSON
     * @return 解析结果，失败或空输入返回空列表，永不为 null
     */
    public List<String> parseJsonArray(String s) {
        return JsonUtil.parseJsonArray(om, s);
    }

    /**
     * 按行程实体结算（人员名单从 {@code Trip#peopleJson} 解析）。
     *
     * <p>刻意<b>不</b>重载成 {@code calculate(Trip, List)}：与
     * {@link #calculate(List, List)} 放在一起会让 {@code calculate(null, items)} 这类调用产生
     * 编译期歧义，独立方法名更安全。
     *
     * @param trip  行程（仅读取 peopleJson，不会修改）
     * @param items 明细列表，允许为 null
     * @return 结算结果 Map（people / transfers / total）
     */
    public Map<String, Object> calculateForTrip(Trip trip, List<TripItem> items) {
        return calculate(parseJsonArray(trip.getPeopleJson()), items);
    }

    /**
     * 纯计算：给定人员名单与明细，算出每人净额与最小转账清单。
     *
     * @param people 行程同行人员名单（决定输出顺序与默认分摊范围），允许为 null
     * @param items  明细列表，允许为 null
     * @return 结算结果 Map，key 依次为 people / transfers / total
     */
    public Map<String, Object> calculate(List<String> people, List<TripItem> items) {
        List<String> safePeople = people == null ? Collections.emptyList() : people;
        List<TripItem> safeItems = items == null ? Collections.emptyList() : items;

        final BigDecimal ZERO = BigDecimal.ZERO;
        Map<String, BigDecimal> paid = new LinkedHashMap<>();
        Map<String, BigDecimal> share = new LinkedHashMap<>();
        for (String p : safePeople) {
            paid.put(p, ZERO);
            share.put(p, ZERO);
        }

        BigDecimal total = ZERO;
        for (TripItem it : safeItems) {
            BigDecimal amt = it.getAmt() == null ? ZERO : it.getAmt();
            if (amt.compareTo(ZERO) <= 0) continue;   // 0 元 / 冲正负数的明细不参与分摊
            total = total.add(amt);

            String payer = it.getPayer();
            List<String> parts = parseJsonArray(it.getParticipantsJson());
            if (parts.isEmpty()) parts = new ArrayList<>(safePeople);
            if (parts.isEmpty() && payer != null && !payer.isEmpty()) parts = new ArrayList<>(List.of(payer));

            if (payer != null && !payer.isEmpty()) {
                paid.putIfAbsent(payer, ZERO);
                paid.put(payer, paid.get(payer).add(amt));
            }

            int k = parts.size();
            if (k == 0) continue;   // 无人可分摊（无名单且无付款人）→ 该笔只在 paid/total 留痕

            // 以「分」为单位分摊：先整除，再把余数依次补给前 rem 个人，确保总分摊额 === 总额
            long cents = amt.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValue();
            long base = cents / k;
            long rem = cents % k;
            for (int i = 0; i < k; i++) {
                long c = base + (i < rem ? 1 : 0);
                BigDecimal sh = new BigDecimal(c).movePointLeft(2);
                String pp = parts.get(i);
                share.putIfAbsent(pp, ZERO);
                paid.putIfAbsent(pp, ZERO);
                share.put(pp, share.get(pp).add(sh));
            }
        }

        // 逐人输出：保持 paid 的插入顺序（先是行程名单顺序，再是名单外的付款人）
        List<Map<String, Object>> peopleOut = new ArrayList<>();
        List<Party> bal = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : paid.entrySet()) {
            String p = e.getKey();
            BigDecimal pai = e.getValue();
            BigDecimal sha = share.getOrDefault(p, ZERO);
            BigDecimal b = pai.subtract(sha);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p);
            m.put("paid", pai);
            m.put("share", sha);
            m.put("balance", b);
            peopleOut.add(m);
            if (b.signum() != 0) bal.add(new Party(p, b));
        }

        List<Party> creditors = bal.stream().filter(x -> x.bal.signum() > 0)
                .sorted(Comparator.comparing((Party x) -> x.bal).reversed()).collect(Collectors.toList());
        List<Party> debtors = bal.stream().filter(x -> x.bal.signum() < 0)
                .sorted(Comparator.comparing((Party x) -> x.bal)).collect(Collectors.toList());

        // 贪心两指针：每轮用最大债权对冲最大债务，得到的最小转账笔数
        List<Map<String, Object>> transfers = new ArrayList<>();
        int ci = 0, di = 0;
        while (ci < creditors.size() && di < debtors.size()) {
            Party c = creditors.get(ci), d = debtors.get(di);
            BigDecimal amt = c.bal.min(d.bal.abs());
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("from", d.name);
            tr.put("to", c.name);
            tr.put("amount", amt);
            transfers.add(tr);
            c.bal = c.bal.subtract(amt);
            d.bal = d.bal.add(amt);
            if (c.bal.signum() == 0) ci++;
            if (d.bal.signum() == 0) di++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("people", peopleOut);
        out.put("transfers", transfers);
        out.put("total", total);
        return out;
    }

    /** 结算过程中的可变债权/债务中间态（正数=应收回，负数=应付出）。 */
    private static final class Party {
        private final String name;
        private BigDecimal bal;

        private Party(String name, BigDecimal bal) {
            this.name = name;
            this.bal = bal;
        }
    }
}
