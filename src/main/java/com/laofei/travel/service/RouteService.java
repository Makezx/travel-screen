package com.laofei.travel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laofei.travel.model.Trip;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路线烘焙服务：把「城市中心对中心」的直线连接器，展开成沿真实道路的密集坐标。
 *
 * <p>设计要点：
 * <ul>
 *   <li>烘焙与浏览分离——结果存进 {@link Trip#getPathJson()}，大屏渲染零联网、零 Key 也能跑；
 *       无 Key / 失败自动退回直线（现状）。</li>
 *   <li>三策略（{@code ROUTE_PROVIDER}）：{@code straight}(默认无 Key) / {@code osrm}(OSM 公共实例，无 Key) /
 *       {@code amap}(高德 WebService，需 {@code ROUTE_AMAP_KEY})。</li>
 *   <li>坐标系统一为 GCJ-02：底图 china.json 与 city-coords.json 均为 GCJ-02。
 *       OSRM 返回 WGS-84，请求前把端点转 WGS-84、结果转回 GCJ-02，保证路线贴合城市点。</li>
 *   <li>内存缓存（按城市序列签名）避免重复调 API；首屏冷启动由 BootstrapService 后台回填。</li>
 * </ul>
 */
@Service
public class RouteService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RouteService.class);

    private final String provider;
    private final String amapKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper om = new ObjectMapper();

    private final Map<String, double[]> cityCoords = new HashMap<>();   // 简称 -> [lat,lng]
    private final Map<String, Long> nameToAdcode = new HashMap<>();     // 简称 -> adcode
    private final Map<Long, String> adcodeName = new HashMap<>();       // adcode -> 全称
    private final Map<Long, double[]> adcodeCenter = new HashMap<>();   // adcode -> [lat,lng]
    private final Map<String, List<double[]>> cache = new ConcurrentHashMap<>();

    public RouteService(@Value("${ROUTE_PROVIDER:straight}") String provider,
                        @Value("${ROUTE_AMAP_KEY:}") String amapKey) {
        this.provider = (provider == null ? "straight" : provider.trim().toLowerCase());
        this.amapKey = (amapKey == null ? "" : amapKey.trim());
        loadResources();
    }

    @PostConstruct
    void logProvider() {
        log.info("[route] 路线烘焙策略={}{}", provider, "amap".equals(provider) ? (amapKey.isBlank() ? "(未配置 Key→退回直线)" : "") : "");
    }

    /* ===================== 对外 API ===================== */

    /**
     * 给真实 Trip 烘焙路线，返回 JSON 字符串（[lng,lat][]）。
     * 失败 / 不足 2 个有效城市点返回 null（调用方保持原 pathJson / 退回直线）。
     */
    public String bakeForTrip(Trip trip) {
        if (trip == null) return null;
        List<double[]> wps = orderedWaypoints(parseJsonArray(trip.getCitiesJson()));
        if (wps.size() < 2) return null;
        return toJson(expand(wps));
    }

    /**
     * 供前端「每段交通方式图标」用：按顺序返回途经城市中心 [lng,lat]（不抽稀、不烘焙）。
     * 与密集 path 不同——它是「腿」的控制点，前端据此在每段中点画一个交通方式图标。
     */
    public List<double[]> waypointsForCities(List<String> cities) {
        return orderedWaypoints(cities);
    }

    /** 给 mock/示例行程用：给定城市名（已按顺序），展开密集 path（带缓存）。 */
    public List<double[]> denseForCities(List<String> cities) {
        List<double[]> wps = orderedWaypoints(cities);
        if (wps.size() < 2) return wps;
        String key = provider + "|" + String.join(",", cities);
        return cache.computeIfAbsent(key, k -> expand(wps));
    }

    public static List<double[]> parsePathJson(String s) {
        List<double[]> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        try {
            for (JsonNode n : new ObjectMapper().readTree(s)) {
                if (n.isArray() && n.size() >= 2) out.add(new double[]{n.get(0).asDouble(), n.get(1).asDouble()});
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static String toJson(List<double[]> p) {
        if (p == null || p.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < p.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("[").append(p.get(i)[0]).append(",").append(p.get(i)[1]).append("]");
        }
        return sb.append("]").toString();
    }

    /* ===================== 内部 ===================== */

    private List<double[]> orderedWaypoints(List<String> cities) {
        List<double[]> out = new ArrayList<>();
        for (String name : cities) {
            if (name == null || name.isBlank()) continue;
            double[] xy = resolve(name);            // [lat,lng]
            if (xy == null) continue;
            double[] ll = new double[]{xy[1], xy[0]}; // -> [lng,lat]
            // 仅折叠相邻重复点（往返行程的折返城市保留）
            if (!out.isEmpty()) {
                double[] last = out.get(out.size() - 1);
                if (Math.abs(last[0] - ll[0]) < 1e-6 && Math.abs(last[1] - ll[1]) < 1e-6) continue;
            }
            out.add(ll);
        }
        return out;
    }

    private double[] resolve(String name) {
        double[] xy = cityCoords.get(name);
        if (xy != null) return xy;
        Long ad = nameToAdcode.get(name);
        if (ad != null) {
            double[] c = cityCoords.get(shortName(adcodeName.get(ad)));
            if (c != null) return c;
            double[] ac = adcodeCenter.get(ad);
            if (ac != null) return ac;
        }
        return null;
    }

    private List<double[]> expand(List<double[]> wps) {
        if ("straight".equals(provider) || wps.size() < 2) {
            return new ArrayList<>(wps);
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < wps.size() - 1; i++) {
            List<double[]> seg = fetchSegment(wps.get(i), wps.get(i + 1));
            if (seg == null || seg.isEmpty()) {
                if (out.isEmpty() || !same(out.get(out.size() - 1), wps.get(i))) out.add(wps.get(i));
                continue;
            }
            for (int j = 0; j < seg.size(); j++) {
                if (!out.isEmpty() && j == 0 && same(out.get(out.size() - 1), seg.get(0))) continue;
                out.add(seg.get(j));
            }
        }
        double[] last = wps.get(wps.size() - 1);
        if (out.isEmpty() || !same(out.get(out.size() - 1), last)) out.add(last);
        if (out.isEmpty()) return new ArrayList<>(wps);
        // 烘焙路径要存库+反复渲染：用 Douglas-Peucker 抽稀到屏幕可用密度（~700m 容差）
        return simplify(out, SIMPLIFY_TOL);
    }

    private List<double[]> fetchSegment(double[] a, double[] b) {
        try {
            List<double[]> seg;
            if ("amap".equals(provider)) seg = fetchAmap(a, b);
            else if ("osrm".equals(provider)) seg = fetchOsrm(a, b);
            else return null;
            // 高德 QPS 限流 ≤3/s：每段请求后停顿，平滑突发
            if ("amap".equals(provider)) Thread.sleep(AMAP_SEGMENT_DELAY_MS);
            return seg;
        } catch (Exception e) {
            log.warn("[route] 路段获取失败 {}->{}: {}", a, b, e.getMessage());
            return null;
        }
    }

    private List<double[]> fetchOsrm(double[] a, double[] b) throws Exception {
        // OSRM 用 WGS-84：端点先转 WGS-84 请求，结果再转回 GCJ-02
        double[] wa = gcj02ToWgs84(a[0], a[1]);
        double[] wb = gcj02ToWgs84(b[0], b[1]);
        String url = "https://router.project-osrm.org/route/v1/driving/"
                + wa[0] + "," + wa[1] + ";" + wb[0] + "," + wb[1] + "?overview=full&geometries=geojson";
        String body = get(url);
        if (body == null) return null;
        JsonNode routes = om.readTree(body).path("routes");
        if (!routes.isArray() || routes.isEmpty()) return null;
        JsonNode coords = routes.get(0).path("geometry").path("coordinates");
        if (!coords.isArray() || coords.isEmpty()) return null;
        List<double[]> out = new ArrayList<>();
        for (JsonNode p : coords) {
            double[] g = wgs84ToGcj02(p.get(0).asDouble(), p.get(1).asDouble());
            out.add(g);
        }
        return out;
    }

    /** 高德 WebService QPS 限流：官方限制 ≤3 次/秒，这里取 400ms 留余量（≈2.5 QPS）。 */
    private static final long AMAP_SEGMENT_DELAY_MS = 400;
    private static final int AMAP_MAX_TRIES = 5;

    private List<double[]> fetchAmap(double[] a, double[] b) throws Exception {
        if (amapKey.isBlank()) {
            log.warn("[route] amap 策略但未配置 ROUTE_AMAP_KEY，退回直线");
            return null;
        }
        for (int t = 1; t <= AMAP_MAX_TRIES; t++) {
            String url = "https://restapi.amap.com/v3/direction/driving?key=" + URLEncoder.encode(amapKey, "UTF-8")
                    + "&origin=" + a[0] + "," + a[1] + "&destination=" + b[0] + "," + b[1] + "&extensions=base";
            String body = get(url);
            if (body == null) {
                log.warn("[route][amap] 第{}/{}次 body=null(HTTP!=200或异常) {}->{}", t, AMAP_MAX_TRIES, a, b);
                if (t < AMAP_MAX_TRIES) { Thread.sleep(600); continue; }
                return null;
            }
            JsonNode root = om.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                String info = root.path("info").asText();
                String infocode = root.path("infocode").asText();
                // 限流 / 临时错误：退避重试（高德 ≤3 QPS，突发会触 10021）
                if (t < AMAP_MAX_TRIES && isAmapTransient(infocode)) {
                    long back = AMAP_SEGMENT_DELAY_MS * t;
                    log.warn("[route][amap] 临时错误 {} ({}) 第{}/{}次, {}ms 后重试", info, infocode, t, AMAP_MAX_TRIES, back);
                    Thread.sleep(back);
                    continue;
                }
                log.warn("[route][amap] 失败 status={} info={} body={}", root.path("status").asText(), info, body.substring(0, Math.min(200, body.length())));
                return null;
            }
            List<double[]> out = parseAmapPath(root.path("route").path("paths").path(0));
            if (out.isEmpty()) {
                log.warn("[route][amap] polyline 为空 第{}/{}次 {}->{}", t, AMAP_MAX_TRIES, a, b);
                if (t < AMAP_MAX_TRIES) { Thread.sleep(400); continue; }
                return null;
            }
            log.info("[route][amap] OK 段 {}->{} 点数={}", a, b, out.size());
            return out;
        }
        return null;
    }

    /** status=0 但可重试的临时错误码（限流/并发/日配额类）。 */
    private static boolean isAmapTransient(String infocode) {
        if (infocode == null) return false;
        return infocode.equals("10021") || infocode.equals("10044") || infocode.equals("20003")
                || (infocode.startsWith("1002") && infocode.length() == 5);
    }

    /** 解析某条驾车路线：优先 paths[0].polyline(overview)，缺失则拼接 steps[].polyline。
     *  （高德 extensions=base 下 paths[0].polyline 经常为空，真实坐标在 steps[].polyline） */
    private List<double[]> parseAmapPath(JsonNode path0) {
        List<double[]> out = new ArrayList<>();
        if (path0 == null || path0.isMissingNode()) return out;
        String poly = path0.path("polyline").asText();
        if (!poly.isBlank()) {
            appendPolyline(out, poly, true);
        } else {
            JsonNode steps = path0.path("steps");
            if (steps.isArray()) for (JsonNode step : steps) appendPolyline(out, step.path("polyline").asText(), out.isEmpty());
        }
        return out;
    }

    /** 把 "lng,lat;lng,lat" 追加进 out。includeFirst=false 时跳过首点（与上一末点重复）。 */
    private void appendPolyline(List<double[]> out, String poly, boolean includeFirst) {
        if (poly == null || poly.isBlank()) return;
        String[] pts = poly.split(";");
        int start = includeFirst ? 0 : 1;
        for (int i = start; i < pts.length; i++) {
            String[] kv = pts[i].split(",");
            if (kv.length == 2) {
                try { out.add(new double[]{Double.parseDouble(kv[0]), Double.parseDouble(kv[1])}); }
                catch (NumberFormatException ignore) { }
            }
        }
    }

    private String get(String url) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "travel-screen/1.0").GET().build();
            java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) { log.warn("[route] HTTP {} <- {}", resp.statusCode(), url); return null; }
            return resp.body();
        } catch (Exception e) {
            return null;
        }
    }

    /* ===================== 坐标资源 ===================== */

    private void loadResources() {
        try (InputStream g = new ClassPathResource("data/geo.json").getInputStream()) {
            JsonNode cities = om.readTree(g).get("cities");
            if (cities != null) for (JsonNode c : cities) {
                long a = c.get("a").asLong();
                String full = c.get("n").asText();
                nameToAdcode.put(shortName(full), a);
                nameToAdcode.putIfAbsent(aliasName(full), a);
                adcodeName.put(a, full);
                JsonNode cc = c.get("c");
                if (cc != null && cc.isArray() && cc.size() == 2)
                    adcodeCenter.put(a, new double[]{cc.get(1).asDouble(), cc.get(0).asDouble()}); // [lat,lng]
            }
        } catch (Exception e) {
            log.warn("[route] geo.json 加载失败: {}", e.getMessage());
        }
        try (InputStream c = new ClassPathResource("data/city-coords.json").getInputStream()) {
            JsonNode arr = om.readTree(c);
            arr.fields().forEachRemaining(en -> {
                JsonNode v = en.getValue();
                if (v.isArray() && v.size() == 2)
                    cityCoords.put(en.getKey(), new double[]{v.get(0).asDouble(), v.get(1).asDouble()}); // [lat,lng]
            });
        } catch (Exception e) {
            log.warn("[route] city-coords.json 加载失败: {}", e.getMessage());
        }
    }

    /* ===================== 工具 ===================== */

    private static boolean same(double[] x, double[] y) {
        return Math.abs(x[0] - y[0]) < 1e-6 && Math.abs(x[1] - y[1]) < 1e-6;
    }

    /** Douglas-Peucker 抽稀：垂直距离 > tol(度) 的点保留，线路明显拐弯才留点。 */
    private static List<double[]> simplify(List<double[]> pts, double tol) {
        if (pts == null || pts.size() <= 2) return new ArrayList<>(pts);
        boolean[] keep = new boolean[pts.size()];
        keep[0] = keep[pts.size() - 1] = true;
        java.util.Stack<int[]> stack = new java.util.Stack<>();
        stack.push(new int[]{0, pts.size() - 1});
        while (!stack.isEmpty()) {
            int[] seg = stack.pop();
            int a = seg[0], b = seg[1];
            double maxD = -1; int idx = -1;
            for (int i = a + 1; i < b; i++) {
                double d = perpDist(pts.get(i), pts.get(a), pts.get(b));
                if (d > maxD) { maxD = d; idx = i; }
            }
            if (maxD > tol && idx != -1) {
                keep[idx] = true;
                stack.push(new int[]{a, idx});
                stack.push(new int[]{idx, b});
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    private static double perpDist(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) {
            double ex = p[0] - a[0], ey = p[1] - a[1];
            return Math.sqrt(ex * ex + ey * ey);
        }
        double t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = a[0] + t * dx, cy = a[1] + t * dy;
        return Math.sqrt((p[0] - cx) * (p[0] - cx) + (p[1] - cy) * (p[1] - cy));
    }

    private List<String> parseJsonArray(String s) {
        List<String> r = new ArrayList<>();
        if (s == null || s.isBlank()) return r;
        try {
            for (JsonNode n : om.readTree(s)) r.add(n.asText());
        } catch (Exception ignored) { }
        return r;
    }

    private static String shortName(String full) {
        return full.replace("市", "").replace("地区", "").replace("自治州", "")
                .replace("盟", "").replace("特别行政区", "").trim();
    }

    private static final String[] ETHNIC_NAMES = {
            "维吾尔", "柯尔克孜", "乌孜别克", "哈萨克", "塔吉克", "俄罗斯", "鄂温克", "达斡尔", "鄂伦春",
            "土家", "苗族", "藏族", "羌族", "彝族", "白族", "傣族", "景颇", "傈僳", "回族", "蒙古",
            "朝鲜", "布依", "侗族", "黎族", "哈尼", "壮族", "瑶族", "畲族", "水族", "仡佬", "拉祜",
            "佤族", "纳西", "德昂", "阿昌", "普米", "怒族", "独龙", "基诺", "门巴", "珞巴", "布朗",
            "撒拉", "毛南", "京族", "赫哲", "高山", "保安", "裕固", "土族", "满族", "锡伯", "仫佬", "东乡"
    };

    private static String aliasName(String full) {
        String s = shortName(full);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String e : ETHNIC_NAMES) {
                String[] toks = e.endsWith("族") ? new String[]{e} : new String[]{e + "族", e};
                boolean hit = false;
                for (String tok : toks) {
                    if (s.length() > tok.length() && s.endsWith(tok)) {
                        s = s.substring(0, s.length() - tok.length());
                        stripped = true;
                        hit = true;
                        break;
                    }
                }
                if (hit) break;
            }
        }
        return s;
    }

    /* ===================== WGS-84 <-> GCJ-02 ===================== */

    /** 烘焙路径抽稀容差（度）。约 0.006° ≈ 700m：保留明显拐弯，去掉道路抖动。 */
    private static final double SIMPLIFY_TOL = 0.006;

    private static final double GCJ_A = 6378245.0;
    private static final double GCJ_EE = 0.00669342162296594323;

    private static boolean outOfChina(double lng, double lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) return new double[]{lng, lat};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - GCJ_EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    private static double[] gcj02ToWgs84(double lng, double lat) {
        double[] g = wgs84ToGcj02(lng, lat);
        return new double[]{lng - (g[0] - lng), lat - (g[1] - lat)};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
