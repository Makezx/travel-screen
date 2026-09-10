package com.laofei.travel.web;

/**
 * AI 调用被限流 / 并发占满时抛出。
 * 两个 AI 端点（/api/ai/plan、/api/ai/photo）都 catch Exception 并返回 {ok:false,error:...} + HTTP 200，
 * 与既有 AI 失败口径一致（前端统一看 ok 字段），不会暴露成 500。
 */
public class AiRateLimitException extends RuntimeException {

    public AiRateLimitException(String message) {
        super(message);
    }
}
