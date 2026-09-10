package com.laofei.travel.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON 数组解析的公共实现。
 *
 * <p>行程的同行人员（{@code Trip#peopleJson}）与明细的参与分摊人（{@code TripItem#participantsJson}）
 * 都存成 JSON 数组字符串，且两处都必须允许「空 / null / 非法 JSON」优雅退化成空列表。
 * 以前这份逻辑是 {@code ApiController} 的私有方法，AA 结算抽成独立服务后若各留一份，
 * 很容易出现「人员解析」与「分摊人解析」行为漂移的隐藏 bug，因此统一收敛到这里。
 */
public final class JsonUtil {

    private JsonUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 把 JSON 数组字符串解析成字符串列表；任何不可解析的输入一律返回空列表（不抛异常）。
     *
     * @param om 复用的 ObjectMapper（由 Spring 容器注入，避免多处各自 new）
     * @param s  形如 {@code ["张三","李四"]} 的字符串，允许为 null / 空白 / 非法 JSON
     * @return 解析出的元素列表；解析失败时返回空列表，永不为 null
     */
    public static List<String> parseJsonArray(ObjectMapper om, String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        try {
            List<String> r = new ArrayList<>();
            for (var n : om.readTree(s)) r.add(n.asText());
            return r;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
