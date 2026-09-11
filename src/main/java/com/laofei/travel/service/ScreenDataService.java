package com.laofei.travel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laofei.travel.model.Team;
import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripPhoto;
import lombok.RequiredArgsConstructor;
import com.laofei.travel.model.TripItem;
import com.laofei.travel.repository.TeamRepository;
import com.laofei.travel.repository.TripItemRepository;
import com.laofei.travel.repository.TripPhotoRepository;
import com.laofei.travel.repository.TripRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 由数据库计算大屏所需的 screen_data（与原来 Python 产出的结构完全一致）。
 * 前端只改数据获取方式，渲染逻辑零改动。
 */
@Service
@RequiredArgsConstructor
public class ScreenDataService {

    private final TripRepository tripRepo;
    private final RouteService routeService;
    private final TripItemRepository itemRepo;
    private final TeamRepository teamRepo;
    private final TripPhotoRepository photoRepo;
    private final ObjectMapper om = new ObjectMapper();

    private JsonNode geoNode;
    private final Map<String, double[]> cityCoords = new HashMap<>();   // 简称 -> [lat,lng]
    private final Map<String, Long> nameToAdcode = new HashMap<>();     // 简称 -> adcode
    private final Map<Long, String> adcodeName = new HashMap<>();       // adcode -> 全称
    private final Map<Long, double[]> adcodeCenter = new HashMap<>();   // adcode -> [lat,lng]，来自 geo.cities 的 c 字段（坐标兜底）

    private static final Pattern DATE_PAT = Pattern.compile("(\\d{4})[.\\-](\\d{1,2})[.\\-](\\d{1,2})");

    @PostConstruct
    void loadResources() throws Exception {
        try (InputStream g = new ClassPathResource("data/geo.json").getInputStream()) {
            geoNode = om.readTree(g);
        }
        try (InputStream c = new ClassPathResource("data/city-coords.json").getInputStream()) {
            JsonNode arr = om.readTree(c);
            arr.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                cityCoords.put(e.getKey(), new double[]{v.get(0).asDouble(), v.get(1).asDouble()});
            });
        }
        // 从 geo.cities 建 简称<->adcode 映射，并记下城市中心点（geo 的 c 为 [lng,lat]，统一转成 [lat,lng]）
        JsonNode cities = geoNode.get("cities");
        if (cities != null) {
            for (JsonNode c : cities) {
                long a = c.get("a").asLong();
                String full = c.get("n").asText();
                nameToAdcode.put(shortName(full), a);
                // 追加民族自治州的自然写法（恩施/阿坝/延边/海西…）。
                // 只 putIfAbsent，不动上面那个精确 key —— 存量数据里两种写法都能解析。
                nameToAdcode.putIfAbsent(aliasName(full), a);
                adcodeName.put(a, full);
                JsonNode cc = c.get("c");
                if (cc != null && cc.size() >= 2) {
                    adcodeCenter.put(a, new double[]{cc.get(1).asDouble(), cc.get(0).asDouble()});
                }
            }
        }
        // geo.place2adcode 是「景区/县/区 -> 所属地级市 adcode」的别名表（千岛湖→杭州、喀纳斯→阿勒泰…）。
        // 以前这张表从未被加载，导致 42 个游记城市里 41 个查不到 adcode：不亮光柱、不连线、还被判为 unresolved。
        // 这里合并进 nameToAdcode（不覆盖已有地级市映射），坐标与名称由所属地级市提供。
        JsonNode p2a = geoNode.get("place2adcode");
        if (p2a != null) {
            p2a.fields().forEachRemaining(e -> {
                long ad = e.getValue().asLong();
                if (adcodeName.containsKey(ad)) nameToAdcode.putIfAbsent(e.getKey(), ad);
            });
        }
    }

    private static String shortName(String full) {
        return full.replace("市", "").replace("地区", "").replace("自治州", "")
                .replace("盟", "").replace("特别行政区", "").trim();
    }

    /**
     * 民族名清单，用于把自治州的自然写法还原出来。
     * 注意：不能用正则 {@code (?:[\u4e00-\u9fa5]{1,3}族)+$} —— 它取最左匹配，
     * "海西蒙古族藏族" 会被剥成 "海"（应为 "海西"）。这里用显式表逐个从尾部剥离，行为可控。
     */
    private static final String[] ETHNIC_NAMES = {
            "维吾尔", "柯尔克孜", "乌孜别克", "哈萨克", "塔吉克", "俄罗斯", "鄂温克", "达斡尔", "鄂伦春",
            "土家", "苗族", "藏族", "羌族", "彝族", "白族", "傣族", "景颇", "傈僳", "回族", "蒙古",
            "朝鲜", "布依", "侗族", "黎族", "哈尼", "壮族", "瑶族", "畲族", "水族", "仡佬", "拉祜",
            "佤族", "纳西", "德昂", "阿昌", "普米", "怒族", "独龙", "基诺", "门巴", "珞巴", "布朗",
            "撒拉", "毛南", "京族", "赫哲", "高山", "保安", "裕固", "土族", "满族", "锡伯", "仫佬", "东乡"
    };

    /**
     * 城市别称：在 shortName 基础上继续剥掉尾部民族名。
     * 恩施土家族苗族自治州 → 恩施；海西蒙古族藏族自治州 → 海西；大理白族自治州 → 大理。
     * 用户实际记城市很少写全称，这一步让「恩施」「阿坝」「延边」这类输入也能点亮。
     */
    private static String aliasName(String full) {
        String s = shortName(full);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String e : ETHNIC_NAMES) {
                // 官方名里民族既可能带「族」也可能不带：海西蒙古族藏族自治州 / 博尔塔拉蒙古自治州、
                // 伊犁哈萨克自治州。带「族」的先试，再试裸写法，避免把「蒙古族」误剥成「蒙古」后残留。
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

    private static double r2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public Map<String, Object> build() {
        return build(null);
    }

    /**
     * 按团队聚合大屏数据。
     * teamId 为 null / 0 → 最新团队（created_at 最大）；有值 → 该团队行程。
     * 无团队（或解析不到）→ 全量兜底，兼容旧行为。
     * <p>
     * 合并后：顶层「活动/大行程」容器本身不进大屏行程列表，仅展示叶子（子行程 / 未分组行程）。
     */
    public Map<String, Object> build(Long teamId) {
        Team team = resolveTeam(teamId);
        List<Trip> all = (team == null) ? tripRepo.findAll() : tripRepo.findByTeamId(team.getId());
        Set<Long> containerIds = all.stream()
                .filter(t -> t.getParentId() != null)
                .map(Trip::getParentId)
                .collect(java.util.stream.Collectors.toSet());
        List<Trip> visible = all.stream()
                .filter(t -> !containerIds.contains(t.getId()))
                .collect(java.util.stream.Collectors.toList());
        return buildFromTrips(visible);
    }

    /** 合并后：按顶层行程（活动）聚合大屏数据（parentId 为空则回退全量） */
    public Map<String, Object> buildByParent(Long parentId) {
        List<Trip> all = (parentId == null) ? tripRepo.findAll() : tripRepo.findByParentId(parentId);
        return buildFromTrips(all);
    }

    private Map<String, Object> buildFromTrips(List<Trip> all) {
        // 稳定排序：年 -> 日期 -> 计划排最后
        // 注意：日期必须按「数值」比而非字符串比，否则 "2023.10.19" 会排在 "2023.8.6" 前面（巡演顺序错乱）
        all.sort((x, y) -> {
            int c = Integer.compare(x.getYear() == null ? 0 : x.getYear(), y.getYear() == null ? 0 : y.getYear());
            if (c != 0) return c;
            LocalDate dx = parseStart(x.getDateText());
            LocalDate dy = parseStart(y.getDateText());
            if (dx != null && dy != null) {
                c = dx.compareTo(dy);
            } else if (dx != null) {
                return -1;
            } else if (dy != null) {
                return 1;
            } else {
                c = nullSafe(x.getDateText()).compareTo(nullSafe(y.getDateText()));
            }
            if (c != 0) return c;
            if (!"plan".equals(x.getStatus()) && "plan".equals(y.getStatus())) return -1;
            if ("plan".equals(x.getStatus()) && !"plan".equals(y.getStatus())) return 1;
            return 0;
        });

        // lit: adcode -> {n, c:[lng,lat], v, s, t:[]}
        Map<Long, Map<String, Object>> lit = new LinkedHashMap<>();
        Map<Long, Set<String>> litTrips = new HashMap<>();
        Map<String, Map<String, Object>> litByName = new HashMap<>();

        List<Map<String, Object>> tripsOut = new ArrayList<>();
        Map<String, Map<String, Object>> personAgg = new LinkedHashMap<>();
        int itemCount = 0;
        double totalAll = 0;
        int doneCount = 0, planCount = 0;
        Map<Integer, Map<String, Object>> yearAgg = new LinkedHashMap<>();
        Map<String, Double> catAgg = new LinkedHashMap<>();
        /** geo.json 里查不到 adcode 的城市（不亮光柱 / 不连线），供前端给「N 个城市未识别」提示 */
        Set<String> unresolved = new LinkedHashSet<>();

        int idx = 0;
        for (Trip t : all) {
            idx++;
            String tid = "T" + String.format("%02d", idx);
            boolean done = "done".equals(t.getStatus());

            List<TripItem> items = itemRepo.findByTripId(t.getId());
            itemCount += items.size();

            // 重算 cats / total / avg（以明细为准）
            Map<String, Double> cats = new LinkedHashMap<>();
            double sum = 0;
            for (TripItem it : items) {
                if (it.getAmt() == null) continue;
                double a = it.getAmt().doubleValue();
                sum += a;
                String cat = it.getCat() == null || it.getCat().isBlank() ? "其他" : it.getCat();
                cats.put(cat, cats.getOrDefault(cat, 0.0) + a);
            }
            Double total = done ? r2(sum) : null;
            Double avg = (done && t.getN() != null && t.getN() > 0) ? r2(sum / t.getN()) : null;
            if (done) { totalAll += sum; doneCount++; }
            else planCount++;

            // 人员
            List<String> people = asList(t.getPeopleJson());
            for (String p : people) {
                Map<String, Object> e = personAgg.computeIfAbsent(p, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", k); m.put("trips", 0); m.put("spend", 0.0);
                    m.put("cities", new LinkedHashSet<>()); m.put("years", new TreeSet<>());
                    return m;
                });
                e.put("trips", (int) e.get("trips") + 1);
                e.put("spend", r2((double) e.get("spend") + (avg == null ? 0 : avg)));
                @SuppressWarnings("unchecked") Set<String> cs = (Set<String>) e.get("cities");
                for (String c : asList(t.getCitiesJson())) cs.add(c);
                @SuppressWarnings("unchecked") Set<Integer> ys = (Set<Integer>) e.get("years");
                if (t.getYear() != null) ys.add(t.getYear());
            }

            // 年份聚合
            if (done && t.getYear() != null) {
                Map<String, Object> y = yearAgg.computeIfAbsent(t.getYear(), k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("y", k); m.put("n", 0); m.put("s", 0.0); return m;
                });
                y.put("n", (int) y.get("n") + 1);
                y.put("s", r2((double) y.get("s") + sum));
            }

            // 分类聚合（仅已完成行程：计划行程的假明细不该进分类饼图）
            if (done) {
                for (Map.Entry<String, Double> e : cats.entrySet()) {
                    catAgg.put(e.getKey(), catAgg.getOrDefault(e.getKey(), 0.0) + e.getValue());
                }
            }

            // route / path
            List<Long> route = new ArrayList<>();
            List<double[]> path;
            Set<Long> tripAdcodes = new LinkedHashSet<>();
            for (String cname : asList(t.getCitiesJson())) {
                Long ad = nameToAdcode.get(cname);
                if (ad != null) { route.add(ad); tripAdcodes.add(ad); }
                else unresolved.add(cname); // geo 底图查不到 → 不亮不连线，前端据此提示
            }
            // 路线坐标：优先用已烘焙的真实道路 pathJson；缺失则退回城市中心对中心直线
            String baked = t.getPathJson();
            if (baked != null && !baked.isBlank()) {
                path = RouteService.parsePathJson(baked);
            } else {
                path = new ArrayList<>();
                for (String cname : asList(t.getCitiesJson())) {
                    double[] xy = cityCoords.get(cname);
                    if (xy == null) { Long ad = nameToAdcode.get(cname); if (ad != null) xy = findCoord(ad); }
                    if (xy != null) path.add(new double[]{xy[1], xy[0]}); // [lng,lat]
                }
            }

            // lit 累计（仅已完成）
            if (done) {
                double share = (t.getN() != null && t.getN() > 0) ? sum / t.getN() : 0;
                for (Long ad : tripAdcodes) {
                    Map<String, Object> m = lit.computeIfAbsent(ad, k -> {
                        Map<String, Object> nm = new LinkedHashMap<>();
                        String full = adcodeName.getOrDefault(k, String.valueOf(k));
                        nm.put("n", full);
                        nm.put("sn", aliasName(full)); // 显示用短名：恩施土家族苗族自治州 -> 恩施
                        nm.put("c", null);
                        nm.put("v", 0); nm.put("s", 0.0); nm.put("t", new ArrayList<String>());
                        return nm;
                    });
                    m.put("v", (int) m.get("v") + 1);
                    m.put("s", r2((double) m.get("s") + share));
                    litTrips.computeIfAbsent(ad, k -> new LinkedHashSet<>()).add(tid);
                }
            }

            // 输出 trip
            Map<String, Object> to = new LinkedHashMap<>();
            to.put("id", tid);
            to.put("nid", t.getId());
            to.put("sheet", t.getSheet());
            to.put("title", t.getTitle());
            to.put("date", t.getDateText());
            to.put("year", t.getYear());
            to.put("status", t.getStatus());
            to.put("people", people);
            to.put("n", t.getN() == null ? 0 : t.getN());
            to.put("cities", asList(t.getCitiesJson()));
            to.put("total", total);
            to.put("avg", avg);
            to.put("cats", cats);
            to.put("route", route);
            to.put("path", path);
            // 途经城市控制点 [lng,lat]：前端据此在每段中点画一个交通方式图标（避免对密集 path 逐点画）
            to.put("waypoints", routeService.waypointsForCities(asList(t.getCitiesJson())));
            to.put("notes", asList(t.getNotesJson()));
            List<Map<String, Object>> itemsOut = new ArrayList<>();
            for (TripItem it : items) {
                Map<String, Object> io = new LinkedHashMap<>();
                io.put("d", it.getD());
                io.put("cat", it.getCat());
                io.put("name", it.getName());
                io.put("amt", it.getAmt() == null ? 0 : it.getAmt().doubleValue());
                io.put("payer", it.getPayer());
                io.put("note", it.getNote());
                itemsOut.add(io);
            }
            to.put("items", itemsOut);
            tripsOut.add(to);
        }

        // 回填 lit.t 与 lit.c
        for (Map.Entry<Long, Map<String, Object>> e : lit.entrySet()) {
            Long ad = e.getKey();
            Map<String, Object> m = e.getValue();
            m.put("t", new ArrayList<>(litTrips.getOrDefault(ad, Collections.emptySet())));
            double[] xy = findCoord(ad);
            if (xy != null) m.put("c", new double[]{xy[1], xy[0]}); // [lng,lat]
            litByName.put(String.valueOf(ad), m);
        }

        // citiesXY：城市名字标签用。覆盖全部地级市（geo 中心点兜底），
        // 否则新解析出的城市能点亮、能连线，却画不出名字标签。
        Map<String, double[]> citiesXY = buildCityXY(); // [lat,lng]

        // people 收尾
        List<Map<String, Object>> peopleOut = new ArrayList<>();
        for (Map<String, Object> e : personAgg.values()) {
            @SuppressWarnings("unchecked") Set<String> cs = (Set<String>) e.get("cities");
            @SuppressWarnings("unchecked") Set<Integer> ys = (Set<Integer>) e.get("years");
            e.put("cities", new ArrayList<>(cs));
            e.put("years", new ArrayList<>(ys));
            e.put("spend", r2((double) e.get("spend")));
            peopleOut.add(e);
        }
        peopleOut.sort((a, b) -> (int) b.get("trips") - (int) a.get("trips"));

        // stat
        int cityCount = lit.size();
        int cityTrips = lit.values().stream().mapToInt(m -> (int) m.get("v")).sum();
        int places = (int) tripsOut.stream()
                .filter(t -> "done".equals(t.get("status")))
                .flatMap(t -> ((List<?>) t.get("cities")).stream())
                .distinct().count();
        double avgAll = doneCount > 0 ? r2(totalAll / doneCount) : 0;
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("trips", doneCount);
        stat.put("plans", planCount);
        stat.put("cities", cityCount);
        stat.put("places", places);
        stat.put("cityTrips", cityTrips);
        stat.put("total", r2(totalAll));
        stat.put("items", itemCount);
        stat.put("avg", avgAll);
        stat.put("people", peopleOut.size());
        Map<String, Double> catsOut = new LinkedHashMap<>();
        catAgg.entrySet().stream().sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> catsOut.put(e.getKey(), r2(e.getValue())));
        stat.put("cats", catsOut);
        List<Map<String, Object>> yearsOut = new ArrayList<>(yearAgg.values());
        yearsOut.sort((a, b) -> (int) a.get("y") - (int) b.get("y"));
        stat.put("years", yearsOut);
        stat.put("span", span(all));

        // nextup（计划行程倒计时）
        List<Map<String, Object>> nextup = new ArrayList<>();
        for (Map<String, Object> t : tripsOut) {
            if (!"plan".equals(t.get("status"))) continue;
            String dt = (String) t.get("date");
            LocalDate start = parseStart(dt);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.get("id"));
            m.put("title", t.get("title"));
            m.put("date", dt);
            m.put("start", start == null ? null : start.format(DateTimeFormatter.ISO_LOCAL_DATE));
            m.put("days", start == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), start));
            m.put("cities", t.get("cities"));
            nextup.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trips", tripsOut);
        out.put("photos", photosOf(all)); // 照片仅输出白名单字段（city/fileName/caption/url/thumbUrl），不泄露 tripId 之外内部信息
        out.put("unresolvedCities", new ArrayList<>(unresolved)); // geo 底图未覆盖的城市（前端给轻量提示）
        out.put("people", peopleOut);
        out.put("citiesXY", citiesXY);
        // 注意：底图不再塞进本响应体。底图随行程数无关，且已扩到 369 个地级市（约 1MB），
        // 每次切活动/团队都重传纯属浪费。改由 GET /api/geo 单独下发 + 客户端按天缓存。
        out.put("lit", litByName);
        out.put("stat", stat);
        out.put("nextup", nextup);
        return out;
    }

    /** 大屏照片数组：scope 内行程的照片，只输出 {city, fileName, caption, url} 白名单字段 */
    private List<Map<String, Object>> photosOf(List<Trip> trips) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Trip t : trips) {
            // 附带所属行程的到访城市：照片 city 为空时，前端按「行程到访城市」也能在地图点开照片墙
            List<String> tripCities = asList(t.getCitiesJson());
            for (TripPhoto p : photoRepo.findByTripId(t.getId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("city", p.getCity());
                m.put("fileName", p.getFileName());
                m.put("caption", p.getCaption());
                m.put("url", "/photos/" + p.getFileName());
                // 缩略图（长边 800px）；未生成时为 null，前端回退 url
                m.put("thumbUrl", p.getThumbFileName() == null ? null : "/photos/" + p.getThumbFileName());
                m.put("cities", tripCities);
                out.add(m);
            }
        }
        return out;
    }

    private Team resolveTeam(Long teamId) {
        if (teamId == null || teamId == 0L) {
            return teamRepo.findTopByOrderByCreatedAtDesc().orElse(null);
        }
        return teamRepo.findById(teamId).orElse(null);
    }

    /**
     * 未登录演示数据：与真实 screen_data 结构完全一致（前端渲染零改动），
     * 但人名 / 行程 / 金额全部为脱敏示例，不泄露任何真实数据。带 mock=true 标记。
     */
    public Map<String, Object> buildMock() {
        String[] PEOPLE = {"成员甲", "成员乙", "成员丙", "成员丁", "成员戊"};
        // title / date / year / status / n / citiesCsv / peopleIdx / totalCost
        String[][] TRIPS = {
                {"示例 · 川渝美食线", "2023.4.29", "2023", "done", "4", "成都,重庆", "0,1,2,3", "9600"},
                {"示例 · 江南水乡线", "2023.7.13", "2023", "done", "2", "上海,杭州,苏州,南京", "1,4", "12800"},
                {"示例 · 云贵高原线", "2023.10.19", "2023", "done", "3", "昆明,大理,丽江,贵阳", "0,2", "15200"},
                {"示例 · 湘桂山水线", "2023.12.30", "2023", "done", "2", "长沙,桂林,南宁", "3,4", "8800"},
                {"示例 · 华中人文线", "2024.5.1", "2024", "done", "4", "武汉,郑州", "0,1,3,4", "7600"},
                {"示例 · 京津双城线", "2024.8.6", "2024", "done", "3", "北京,天津", "1,2,4", "11400"},
                {"示例 · 闽海海岸线", "2024.10.2", "2024", "done", "2", "厦门,福州", "0,4", "6900"},
                {"示例 · 西北大环线", "2026.10.1", "2026", "plan", "6", "西安,兰州,西宁", "0,1,2,3,4", "0"}
        };
        String[][] ITEM_TPL = {{"交通", "往返大交通"}, {"住宿", "示例酒店"}, {"餐饮", "当地餐食"}, {"门票", "景区门票"}, {"其他", "市内交通杂项"}};
        double[] RATIO = {0.38, 0.27, 0.18, 0.10, 0.07};

        Map<Long, Map<String, Object>> lit = new LinkedHashMap<>();
        Map<Long, Set<String>> litTrips = new HashMap<>();
        Map<String, Map<String, Object>> litByName = new HashMap<>();
        List<Map<String, Object>> tripsOut = new ArrayList<>();
        Map<String, Map<String, Object>> personAgg = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> yearAgg = new LinkedHashMap<>();
        Map<String, Double> catAgg = new LinkedHashMap<>();
        double totalAll = 0;
        int doneCount = 0, planCount = 0, itemCount = 0;

        for (int i = 0; i < TRIPS.length; i++) {
            String[] d = TRIPS[i];
            String tid = "T" + String.format("%02d", i + 1);
            boolean done = "done".equals(d[3]);
            int n = Integer.parseInt(d[4]);
            int year = Integer.parseInt(d[2]);
            List<String> cities = new ArrayList<>(List.of(d[5].split(",")));
            List<String> people = new ArrayList<>();
            for (String pi : d[6].split(",")) people.add(PEOPLE[Integer.parseInt(pi.trim())]);
            double cost = Double.parseDouble(d[7]);

            Map<String, Double> cats = new LinkedHashMap<>();
            List<Map<String, Object>> itemsOut = new ArrayList<>();
            if (done) {
                for (int k = 0; k < ITEM_TPL.length; k++) {
                    double amt = r2(cost * RATIO[k]);
                    cats.merge(ITEM_TPL[k][0], amt, Double::sum);
                    Map<String, Object> io = new LinkedHashMap<>();
                    io.put("d", ""); io.put("cat", ITEM_TPL[k][0]); io.put("name", ITEM_TPL[k][1]);
                    io.put("amt", amt); io.put("payer", ""); io.put("note", "示例数据");
                    itemsOut.add(io);
                }
                itemCount += itemsOut.size();
                totalAll += cost;
                doneCount++;
            } else {
                planCount++;
            }

            for (String p : people) {
                double avg = done && n > 0 ? r2(cost / n) : 0;
                Map<String, Object> e = personAgg.computeIfAbsent(p, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", k); m.put("trips", 0); m.put("spend", 0.0);
                    m.put("cities", new LinkedHashSet<>()); m.put("years", new TreeSet<>());
                    return m;
                });
                e.put("trips", (int) e.get("trips") + 1);
                e.put("spend", r2((double) e.get("spend") + avg));
                @SuppressWarnings("unchecked") Set<String> cs = (Set<String>) e.get("cities");
                cs.addAll(cities);
                @SuppressWarnings("unchecked") Set<Integer> ys = (Set<Integer>) e.get("years");
                ys.add(year);
            }
            if (done) {
                Map<String, Object> y = yearAgg.computeIfAbsent(year, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("y", k); m.put("n", 0); m.put("s", 0.0);
                    return m;
                });
                y.put("n", (int) y.get("n") + 1);
                y.put("s", r2((double) y.get("s") + cost));
                for (Map.Entry<String, Double> e : cats.entrySet())
                    catAgg.merge(e.getKey(), e.getValue(), Double::sum);
            }

            List<Long> route = new ArrayList<>();
            Set<Long> tripAdcodes = new LinkedHashSet<>();
            for (String c : cities) {
                Long ad = nameToAdcode.get(c);
                if (ad != null) { route.add(ad); tripAdcodes.add(ad); }
            }
            // 示例行程路线：用 RouteService 展开真实道路（带缓存；无 Key 时退回直线）
            List<double[]> path = routeService.denseForCities(cities);
            if (done) {
                double share = n > 0 ? r2(cost / n) : 0;
                for (Long ad : tripAdcodes) {
                    Map<String, Object> m = lit.computeIfAbsent(ad, k -> {
                        Map<String, Object> nm = new LinkedHashMap<>();
                        String full = adcodeName.getOrDefault(k, String.valueOf(k));
                        nm.put("n", full);
                        nm.put("sn", aliasName(full)); // 显示用短名，与真实路径保持一致
                        nm.put("c", null); nm.put("v", 0); nm.put("s", 0.0); nm.put("t", new ArrayList<String>());
                        return nm;
                    });
                    m.put("v", (int) m.get("v") + 1);
                    m.put("s", r2((double) m.get("s") + share));
                    litTrips.computeIfAbsent(ad, k -> new LinkedHashSet<>()).add(tid);
                }
            }

            Map<String, Object> to = new LinkedHashMap<>();
            to.put("id", tid);
            to.put("sheet", "示例");
            to.put("title", d[0]);
            to.put("date", d[1]);
            to.put("year", year);
            to.put("status", d[3]);
            to.put("people", people);
            to.put("n", n);
            to.put("cities", cities);
            to.put("total", done ? r2(cost) : null);
            to.put("avg", done && n > 0 ? r2(cost / n) : null);
            to.put("cats", cats);
            to.put("route", route);
            to.put("path", path);
            to.put("waypoints", routeService.waypointsForCities(cities));
            to.put("notes", List.of("演示用脱敏示例数据"));
            to.put("items", itemsOut);
            tripsOut.add(to);
        }

        for (Map.Entry<Long, Map<String, Object>> e : lit.entrySet()) {
            Map<String, Object> m = e.getValue();
            m.put("t", new ArrayList<>(litTrips.getOrDefault(e.getKey(), Collections.emptySet())));
            double[] xy = findCoord(e.getKey());
            if (xy != null) m.put("c", new double[]{xy[1], xy[0]});
            litByName.put(String.valueOf(e.getKey()), m);
        }

        Map<String, double[]> citiesXY = new LinkedHashMap<>();
        // 仅输出 mock 涉及城市，避免泄露真实到访城市集合
        Set<String> mockCities = new LinkedHashSet<>();
        for (Map<String, Object> t : tripsOut) mockCities.addAll((List<String>) t.get("cities"));
        buildCityXY().forEach((k, v) -> {
            if (mockCities.contains(k)) citiesXY.put(k, v);
        });

        List<Map<String, Object>> peopleOut = new ArrayList<>();
        for (Map<String, Object> e : personAgg.values()) {
            @SuppressWarnings("unchecked") Set<String> cs = (Set<String>) e.get("cities");
            @SuppressWarnings("unchecked") Set<Integer> ys = (Set<Integer>) e.get("years");
            e.put("cities", new ArrayList<>(cs));
            e.put("years", new ArrayList<>(ys));
            e.put("spend", r2((double) e.get("spend")));
            peopleOut.add(e);
        }
        peopleOut.sort((a, b) -> (int) b.get("trips") - (int) a.get("trips"));

        double avgAll = doneCount > 0 ? r2(totalAll / doneCount) : 0;
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("trips", doneCount);
        stat.put("plans", planCount);
        stat.put("cities", lit.size());
        stat.put("places", (int) tripsOut.stream().filter(t -> "done".equals(t.get("status")))
                .flatMap(t -> ((List<?>) t.get("cities")).stream()).distinct().count());
        stat.put("cityTrips", lit.values().stream().mapToInt(m -> (int) m.get("v")).sum());
        stat.put("total", r2(totalAll));
        stat.put("items", itemCount);
        stat.put("avg", avgAll);
        stat.put("people", peopleOut.size());
        Map<String, Double> catsOut = new LinkedHashMap<>();
        catAgg.entrySet().stream().sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> catsOut.put(e.getKey(), r2(e.getValue())));
        stat.put("cats", catsOut);
        List<Map<String, Object>> yearsOut = new ArrayList<>(yearAgg.values());
        yearsOut.sort((a, b) -> (int) a.get("y") - (int) b.get("y"));
        stat.put("years", yearsOut);
        stat.put("span", "2023.04 - 2026.10");

        List<Map<String, Object>> nextup = new ArrayList<>();
        for (Map<String, Object> t : tripsOut) {
            if (!"plan".equals(t.get("status"))) continue;
            String dt = (String) t.get("date");
            LocalDate start = parseStart(dt);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.get("id"));
            m.put("title", t.get("title"));
            m.put("date", dt);
            m.put("start", start == null ? null : start.format(DateTimeFormatter.ISO_LOCAL_DATE));
            m.put("days", start == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), start));
            m.put("cities", t.get("cities"));
            nextup.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mock", true);
        out.put("trips", tripsOut);
        out.put("photos", List.of());
        out.put("unresolvedCities", List.of());
        out.put("people", peopleOut);
        out.put("citiesXY", citiesXY);
        // 底图同样由 GET /api/geo 下发（公开地理数据，无行程隐私）
        out.put("lit", litByName);
        out.put("stat", stat);
        out.put("nextup", nextup);
        return out;
    }

    /** adcode -> [lat,lng]：优先 city-coords 表，缺失时回退 geo.cities 自带的城市中心点 c（69 市里有 15 个坐标表没覆盖） */
    private double[] findCoord(Long adcode) {
        String full = adcodeName.get(adcode);
        if (full == null) return null;
        double[] xy = cityCoords.get(shortName(full));
        if (xy != null) return xy;
        return adcodeCenter.get(adcode);
    }

    /**
     * 全部地级市坐标表：key=城市简称，value=[lat,lng]。
     * geo 自带的城市中心点打底（覆盖 369 个市），city-coords.json 的人工微调值优先覆盖
     * （千岛湖/武功山/喀纳斯这类非行政区点位，以及个别需要手工校正的市级坐标）。
     */
    private Map<String, double[]> buildCityXY() {
        Map<String, double[]> m = new LinkedHashMap<>();
        adcodeName.forEach((a, full) -> {
            double[] ctr = adcodeCenter.get(a);
            if (ctr == null) return;
            double[] v = new double[]{ctr[0], ctr[1]};
            m.put(shortName(full), v);
            m.putIfAbsent(aliasName(full), v); // 自然写法（恩施/阿坝/延边…）也要能查到
        });
        m.putAll(cityCoords); // 人工微调优先
        return m;
    }

    /**
     * 公开底图：省份边界 + 地级市多边形。
     * 刻意【不】含 place2adcode —— 它是「景区/县 -> 所属市」的别名表，
     * 内容来自真实行程（千岛湖、武功山…），对外等同于泄露作者去过哪些地方。
     * 前端也从不读它，故只服务端内部使用。
     */
    public JsonNode publicGeo() {
        ObjectNode o = om.createObjectNode();
        o.set("provinces", geoNode == null ? om.createArrayNode() : geoNode.get("provinces"));
        o.set("cities", geoNode == null ? om.createArrayNode() : geoNode.get("cities"));
        return o;
    }

    private static List<String> asList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> r = new ArrayList<>();
            for (JsonNode n : new ObjectMapper().readTree(json)) r.add(n.asText());
            return r;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    /**
     * 从日期文本里取月份（1-12）。
     * 旧实现用 "(\d{1,2})" 直接匹配，"2023.8.6" 会先命中年份里的 "20" → 大屏头部显示 "2023.20"。
     * 这里改按完整日期模式取第 2 段，取不到再退回 1 月。
     */
    private static int monthOf(String dt) {
        Matcher m = DATE_PAT.matcher(nullSafe(dt));
        if (m.find()) {
            try {
                int mm = Integer.parseInt(m.group(2));
                if (mm >= 1 && mm <= 12) return mm;
            } catch (Exception ignored) {
                // 日期超范围（如 2023.13.1）→ 按 1 月兜底
            }
        }
        return 1;
    }

    private static LocalDate parseStart(String dt) {
        if (dt == null) return null;
        Matcher m = DATE_PAT.matcher(dt);
        if (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (Exception e) { return null; }
        }
        return null;
    }

    private static String span(List<Trip> all) {
        Integer minY = null, minM = null, maxY = null, maxM = null;
        for (Trip t : all) {
            if (t.getYear() == null) continue;
            int month = monthOf(t.getDateText());
            if (minY == null || t.getYear() < minY || (t.getYear().equals(minY) && month < minM)) {
                minY = t.getYear(); minM = month;
            }
            if (maxY == null || t.getYear() > maxY || (t.getYear().equals(maxY) && month > maxM)) {
                maxY = t.getYear(); maxM = month;
            }
        }
        if (minY == null) return "";
        return minY + "." + String.format("%02d", minM) + " - " + maxY + "." + String.format("%02d", maxM);
    }
}
