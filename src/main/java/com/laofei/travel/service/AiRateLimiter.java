package com.laofei.travel.service;

import com.laofei.travel.web.AiRateLimitException;
import com.laofei.travel.web.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * AI 调用保护：生产挂的是真实付费 GLM Key，且 demo 账号是公开的，必须防刷。
 * <p>
 * 三层防护：
 * <ol>
 *   <li><b>并发闸门</b>：同步 HttpClient 每次调用要几十秒，无上限时请求会把 Tomcat 线程池打满
 *       （服务先死，账单其次）。用 Semaphore 限制同时在飞的请求数，排队超时即快速失败。</li>
 *   <li><b>分钟级限流</b>：单用户 / 单 IP 每分钟上限，拦脚本连点。</li>
 *   <li><b>天级配额</b>：单用户 / 单 IP 每天上限，拦慢速刷。</li>
 * </ol>
 * 计数为进程内内存实现（单实例部署足够）；多实例时在每个实例上分别生效（总量 = 实例数 × 配额），
 * 如需严格全局配额再换 Redis 实现，接口不变。
 */
@Service
public class AiRateLimiter {

    /** 每分钟单用户/单 IP 允许次数 */
    @Value("${app.ai.rate-per-minute:5}")
    private int perMinute;
    /** 每天单用户/单 IP 允许次数 */
    @Value("${app.ai.rate-per-day:100}")
    private int perDay;
    /** 同时在飞的 AI 请求数上限 */
    @Value("${app.ai.max-concurrency:2}")
    private int maxConcurrency;
    /** 并发槽位排队等待时间（毫秒），超时快速失败而不是堆积线程 */
    @Value("${app.ai.queue-timeout-ms:3000}")
    private int queueTimeoutMs;

    private final SecuritySupport sec;
    private Semaphore slots;
    /** key -> 最近调用时间戳（毫秒），同一 deque 同时服务分钟窗与天窗 */
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    /** 内存保护：key 数量硬上限，超过就整体清空（避免异常流量把内存打爆） */
    private static final int MAX_KEYS = 20_000;

    public AiRateLimiter(SecuritySupport sec) {
        this.sec = sec;
    }

    /** 并发槽位在配置注入后初始化（构造期 @Value 尚未生效） */
    @jakarta.annotation.PostConstruct
    void init() {
        int permits = Math.max(1, maxConcurrency);
        this.slots = new Semaphore(permits, true); // 公平模式：避免后来的请求饿死先到的
    }

    /**
     * 申请一次 AI 调用（顺序：分钟窗 → 天窗 → 并发槽位）。
     * 通过后由调用方在 finally 里调用 {@link #release()}。
     *
     * @throws AiRateLimitException 超限 / 排队超时
     */
    public void acquire() {
        String ip = clientIp();
        String user = sec == null ? null : sec.principal();
        // demo 账号是公开的，任何人都可登录，因此按 IP 与用户双重计数，两个维度任一超限即拒绝
        checkWindow("ip:" + ip);
        if (user != null && !user.isBlank()) checkWindow("u:" + user);

        boolean got;
        try {
            got = slots.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiRateLimitException("AI 请求被中断，请重试");
        }
        if (!got) throw new AiRateLimitException("AI 服务繁忙，请稍后重试");
    }

    /** 释放并发槽位（必须在 finally 中调用） */
    public void release() {
        slots.release();
    }

    /** 分钟窗与天窗检查：超限直接抛，通过则记录本次时间戳 */
    private void checkWindow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < now - DAY_MS) q.pollFirst();
            if (q.size() >= perDay) {
                throw new AiRateLimitException("今日 AI 调用次数已达上限（" + perDay + " 次/天），请明天再试");
            }
            long minuteAgo = now - MINUTE_MS;
            int inMinute = 0;
            for (Long t : q) if (t >= minuteAgo) inMinute++;
            if (inMinute >= perMinute) {
                throw new AiRateLimitException("AI 调用过于频繁，请稍后再试（上限 " + perMinute + " 次/分钟）");
            }
            q.addLast(now);
        }
        if (hits.size() > MAX_KEYS) hits.clear();
    }

    /** 客户端 IP：与 AuthController 一致，优先 X-Forwarded-For 首段（生产走反代） */
    private static String clientIp() {
        var ra = RequestContextHolder.getRequestAttributes();
        if (!(ra instanceof ServletRequestAttributes sa)) return "unknown";
        HttpServletRequest req = sa.getRequest();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String ip = req.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }
}
