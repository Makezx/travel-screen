package com.laofei.travel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行程规划 AI 入口：调用智谱 GLM（open.bigmodel.cn 兼容接口）。
 * 后期你自己写 agent 时，只需替换 callModel() 的实现即可，其余接口不变。
 * API Key 通过环境变量 AI_API_KEY 注入，绝不写进代码。
 */
@Service
public class AiPlanService {

    private final ObjectMapper om = new ObjectMapper();
    private HttpClient http;
    private final AiRateLimiter limiter;

    @Value("${app.ai.enabled:true}")
    private boolean enabled;
    @Value("${app.ai.base-url}")
    private String baseUrl;
    @Value("${app.ai.api-key}")
    private String apiKey;
    @Value("${app.ai.model}")
    private String model;
    @Value("${app.ai.vision-model:glm-4.6v}")
    private String visionModel;
    @Value("${app.ai.timeout-ms:60000}")
    private int timeoutMs;

    public AiPlanService(AiRateLimiter limiter) {
        this.limiter = limiter;
    }

    @PostConstruct
    void init() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public String plan(String requirement, String context) throws Exception {
        if (!enabled) return "AI 规划功能未启用（app.ai.enabled=false）。";
        if (apiKey == null || apiKey.isBlank()) {
            return "尚未配置 AI_API_KEY（环境变量）。请在启动参数中加入 -DAI_API_KEY=你的智谱Key 后重试。";
        }
        String sys = "你是专业的旅行行程规划师，擅长为中国年轻人设计性价比高、体验丰富的国内/出境路线。" +
                "请根据用户需求，输出结构清晰、可直接落地的逐日行程方案。" +
                "要求：\n" +
                "1. 用 Markdown 标题分级（如「Day 1」「Day 2」），每天给出城市/景点/交通/住宿/餐饮与预算区间；\n" +
                "2. 标注大致人均花费与总预算；\n" +
                "3. 给出最佳出行季节与避坑提示；\n" +
                "4. 若信息不足，给出 2-3 个合理假设并继续规划。\n" +
                "语气轻松、有料、像朋友出主意。";
        String user = "我的需求：" + requirement;
        if (context != null && !context.isBlank()) user += "\n\n已知背景信息：\n" + context;

        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.85);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        ObjectNode m1 = messages.addObject(); m1.put("role", "system"); m1.put("content", sys);
        ObjectNode m2 = messages.addObject(); m2.put("role", "user"); m2.put("content", user);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                .build();

        // 限流 + 并发闸门（付费 Key：先保服务不被打满，再控账单）
        limiter.acquire();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("AI 接口返回 " + resp.statusCode() + "：" + resp.body());
            }
            JsonNode root = om.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode()) {
                throw new RuntimeException("AI 返回结构异常：" + resp.body());
            }
            return content.asText();
        } finally {
            limiter.release();
        }
    }

    /**
     * 照片识别（多模态）：图片转 base64 data URL 走智谱 chat/completions 视觉模型，
     * 提示词强制模型只回 JSON（type/city/name/amount/date/note）。
     * 解析宽容：剥掉 ```json 围栏、截取首尾大括号；失败返回 ok:false 带原始文本。
     * 未配置 AI_API_KEY / 未启用时返回 {ok:false,...}（不抛异常，HTTP 仍为 200）。
     */
    public Map<String, Object> analyzePhoto(String mimeType, byte[] data) {
        if (!enabled) return Map.of("ok", false, "error", "AI 功能未启用（app.ai.enabled=false）");
        if (apiKey == null || apiKey.isBlank()) return Map.of("ok", false, "error", "未配置 AI_API_KEY");
        if (data == null || data.length == 0) return Map.of("ok", false, "error", "图片为空");

        String prompt = "识别旅行相关图片，只返回 JSON：" +
                "{\"type\":\"landmark|ticket|other\",\"city\":\"中文城市名，无法判断则空串\"," +
                "\"name\":\"地标名或消费项目名\",\"amount\":数字或null（仅票据/消费类填写）," +
                "\"date\":\"yyyy.M.d 或 null\",\"note\":\"一句话说明\"}。" +
                "若图里像车票/门票/小票则 type=ticket 并尽量提取金额与日期。不要输出 JSON 以外的任何内容。";

        try {
            ObjectNode body = om.createObjectNode();
            body.put("model", visionModel);
            body.put("temperature", 0.2);
            body.put("stream", false);
            ArrayNode messages = body.putArray("messages");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");
            ObjectNode img = content.addObject();
            img.put("type", "image_url");
            String mime = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;
            img.putObject("image_url").put("url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data));
            ObjectNode txt = content.addObject();
            txt.put("type", "text");
            txt.put("text", prompt);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();

            // 限流 + 并发闸门（与 plan() 同一套保护）
            limiter.acquire();
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } finally {
                limiter.release();
            }
            if (resp.statusCode() != 200) {
                return Map.of("ok", false, "error", "AI 接口返回 " + resp.statusCode() + "：" + truncate(resp.body()));
            }
            JsonNode root = om.readTree(resp.body());
            JsonNode node = root.path("choices").path(0).path("message").path("content");
            if (node.isMissingNode()) {
                return Map.of("ok", false, "error", "AI 返回结构异常");
            }
            String raw = node.asText();

            // 宽容解析：剥 ``` 围栏 → 截取首尾大括号之间的 JSON
            String s = raw.trim();
            if (s.startsWith("```")) {
                s = s.replaceFirst("^```(json)?\\s*", "").replaceFirst("```\\s*$", "").trim();
            }
            int st = s.indexOf('{'), en = s.lastIndexOf('}');
            if (st < 0 || en <= st) {
                return Map.of("ok", false, "error", "AI 未返回有效 JSON", "raw", raw);
            }
            s = s.substring(st, en + 1);
            try {
                JsonNode n = om.readTree(s);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("type", n.path("type").asText("other"));
                out.put("city", n.path("city").asText(""));
                out.put("name", n.path("name").asText(""));
                out.put("amount", amountOrNull(n.path("amount")));
                out.put("date", n.path("date").isValueNode() && !n.path("date").isNull() ? n.path("date").asText() : null);
                out.put("note", n.path("note").asText(""));
                out.put("raw", raw);
                return out;
            } catch (Exception e) {
                return Map.of("ok", false, "error", "AI 返回内容解析失败", "raw", raw);
            }
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "AI 识别失败" : e.getMessage());
        }
    }

    /** amount 兼容：数字直接取；字符串尝试转数字；空 / null 返回 null */
    private static Double amountOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.asDouble();
        try {
            return Double.valueOf(n.asText().replace(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
