package com.laofei.travel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Permission;
import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripItem;
import com.laofei.travel.repository.AppUserRepository;
import com.laofei.travel.repository.TeamRepository;
import com.laofei.travel.repository.TripItemRepository;
import com.laofei.travel.repository.TripRepository;
import com.laofei.travel.repository.TripMemberRepository;
import com.laofei.travel.service.AiPlanService;
import com.laofei.travel.service.DataExportService;
import com.laofei.travel.service.DataImportService;
import com.laofei.travel.service.ScreenDataService;
import com.laofei.travel.service.TeamService;
import com.laofei.travel.service.TripService;
import com.laofei.travel.service.TripAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final TripRepository tripRepo;
    private final TripItemRepository itemRepo;
    private final ScreenDataService screenSvc;
    private final DataImportService importSvc;
    private final DataExportService exportSvc;
    private final AiPlanService aiSvc;
    private final TeamService teamSvc;
    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final ObjectMapper om;
    private final SecuritySupport sec;
    private final TripAccessService tripAccess;
    private final TripService tripSvc;
    private final TripMemberRepository memberRepo;
    private final TransactionTemplate txTemplate;

    public ApiController(TripRepository tripRepo, TripItemRepository itemRepo, ScreenDataService screenSvc,
                         DataImportService importSvc, DataExportService exportSvc, AiPlanService aiSvc, TeamService teamSvc,
                         AppUserRepository userRepo, TeamRepository teamRepo,
                         ObjectMapper om, SecuritySupport sec, TripAccessService tripAccess,
                         TripService tripSvc, TripMemberRepository memberRepo,
                         PlatformTransactionManager txm) {
        this.tripRepo = tripRepo;
        this.itemRepo = itemRepo;
        this.screenSvc = screenSvc;
        this.importSvc = importSvc;
        this.exportSvc = exportSvc;
        this.aiSvc = aiSvc;
        this.teamSvc = teamSvc;
        this.userRepo = userRepo;
        this.teamRepo = teamRepo;
        this.om = om;
        this.sec = sec;
        this.tripAccess = tripAccess;
        this.tripSvc = tripSvc;
        this.memberRepo = memberRepo;
        this.txTemplate = new TransactionTemplate(txm);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * 需求7安全过渡：演示账号 demo@travel.cn 只能查看/编辑其归属的「示例·演示」团队数据，
     * 不可触碰真实团队行程。返回演示团队ID或 null(非演示用户)。
     */
    private Long demoTeamId() {
        String principal = sec.principal();
        if (principal == null || !principal.equals("demo@travel.cn")) return null;
        return teamRepo.findByName("示例·演示").map(t -> t.getId()).orElse(null);
    }

    @GetMapping("/screen-data")
    public Map<String, Object> screenData(@RequestParam(required = false) Long teamId,
                                          @RequestParam(required = false) Long parentId) {
        String principal = sec.principal();
        // 显式选定活动（parentId）→ 视为「分享大屏」：游客也返回真实数据（含照片）。
        // 否则游客在 AuthFilter 放行下只会拿到 buildMock()（photos=[]），照片永远看不到。
        if (parentId != null && parentId > 0) {
            // 已登录才做越权校验；游客按「公开大屏」放行（仅暴露该活动聚合数据，不含私密字段）
            if (principal != null && !tripAccess.canView(parentId, principal)) {
                throw new ForbiddenException("无权查看该行程");
            }
            Map<String, Object> d = screenSvc.buildByParent(parentId);
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("type", "trip");
            scope.put("id", parentId);
            scope.put("name", tripRepo.findById(parentId).map(Trip::getTitle).orElse(""));
            d.put("scope", scope);
            return d;
        }
        // 未登录且无显式活动 scope → 脱敏演示数据（不泄露真实行程/人名/金额）
        if (principal == null) {
            return screenSvc.buildMock();
        }
        // 需求8：未显式选团队时，不默认"最新团队"；取用户默认团队，无归属则用系统主团队(种子)兜底
        if (teamId == null || teamId == 0L) {
            Long def = teamSvc.defaultTeamIdForUser(principal);
            teamId = def != null ? def : teamSvc.primaryTeamId();
        }
        return screenSvc.build(teamId);
    }

    /* ---------------- 行程 CRUD ---------------- */
    @GetMapping("/trips")
    public List<Map<String, Object>> listTrips() {
        // 合并后：/api/trips 返回顶层行程（活动/大行程）列表（含 childCount/totalAmount/myRole 等）
        return tripSvc.list(sec.principal());
    }

    @GetMapping("/trips/{id}")
    public Map<String, Object> getTrip(@PathVariable Long id) {
        // 读接口补鉴权：未登录 / 无权查看（跨团队越权）一律拒绝，防 IDOR 泄露行程与明细
        Trip t = tripAccess.requireView(id, sec.principal());
        Map<String, Object> m = toLight(t);
        m.put("items", itemRepo.findByTripId(id).stream().map(this::toItemMap).collect(Collectors.toList()));
        return m;
    }

    @PostMapping("/trips")
    public Map<String, Object> createTrip(@RequestBody Map<String, Object> body) {
        // 合并后：顶层活动创建（向导，body 含 name）与叶子/子行程创建（记一笔/新增）共用入口
        if (body.containsKey("name")) {
            return tripSvc.create(body, sec.requireLogin());
        }
        Trip t = fromBody(body);
        Long demoT = demoTeamId();
        if (demoT != null) t.setTeamId(demoT); // 需求7：演示账号创建的行程归演示团队
        Long pid = t.getParentId();
        assertTripEditable(pid); // 合并后：挂顶层行程则需该行程编辑权
        if (pid != null && t.getTeamId() == null) {
            Trip parent = tripRepo.findById(pid).orElse(null);
            if (parent != null) t.setTeamId(parent.getTeamId());
        }
        recompute(t);
        Trip saved = tripRepo.save(t);
        return toLight(saved);
    }

    @PutMapping("/trips/{id}")
    public Map<String, Object> updateTrip(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        // 合并后：有成员或有子行程即顶层活动 → 走活动更新（closed/name/description）；否则叶子行程更新
        if (isActivityTrip(id)) {
            return tripSvc.update(id, body, sec.requireLogin());
        }
        Trip t = tripRepo.findById(id).orElseThrow(() -> new NotFoundException("not found"));
        Long demoT = demoTeamId();
        if (demoT != null && !demoT.equals(t.getTeamId())) {
            throw new ForbiddenException("演示账号只能编辑演示团队的行程");
        }
        assertTripEditable(t.getParentId());                             // 原父层：需编辑权
        Long newParent = longOrNull(body.get("parentId"));
        if (newParent != null && !newParent.equals(t.getParentId())) {
            assertTripEditable(newParent);                               // 迁入的新父层：同样需编辑权
        }
        applyBody(t, body);
        if (demoT != null) t.setTeamId(demoT);
        recompute(t);
        return toLight(tripRepo.save(t));
    }

    @DeleteMapping("/trips/{id}")
    public Map<String, Object> deleteTrip(@PathVariable Long id) {
        // 合并后：有成员或存在子行程即视为顶层活动，走活动删除（解挂子行程 + 清成员，权限在 tripSvc.delete 内校验）
        Trip target = tripRepo.findById(id).orElseThrow(() -> new NotFoundException("not found"));
        if (isActivityTrip(id)) {
            if (demoTeamId() != null) throw new ForbiddenException("演示账号不可删除活动");
            return tripSvc.delete(id, sec.requireLogin());
        }
        // 叶子行程删除 —— 原有级联逻辑
        // 权限校验（只读，可能抛异常）——置于事务之外，避免异常把删除事务标记为 rollback-only
        boolean global = false;
        try { sec.require(Permission.MANAGE_TRIP); global = true; } catch (Exception ignored) { }
        if (!global && target.getParentId() != null && !tripAccess.canEdit(target.getParentId(), sec.principal())) {
            throw new ForbiddenException("无权删除该行程，需该行程的编辑者身份");
        }
        Long demoT = demoTeamId();
        if (demoT != null && !demoT.equals(target.getTeamId())) {
            throw new ForbiddenException("演示账号只能删除演示团队的行程");
        }
        // 级联删除在独立事务中执行（避免权限校验异常把事务标记为 rollback-only；且自调用不走 AOP 代理，故用 TransactionTemplate）
        txTemplate.execute(status -> {
            assertTripWritable(id); // V3：活动内行程需该活动编辑权；未挂活动则放行（团队级）
            itemRepo.deleteByTripId(id);
            tripRepo.deleteById(id);
            return null;
        });
        return Map.of("ok", true);
    }

    /* ---------------- 顶层活动：未分组行程 / 成员 / 子行程 ---------------- */

    /** 未分组叶子行程（顶层、无成员、无子行程，即既非活动也非容器），供 admin 单独特辑展示 */
    @GetMapping("/trips/standalone")
    public List<Map<String, Object>> standaloneTrips() {
        List<Trip> all = tripRepo.findAll();
        return all.stream()
                .filter(t -> t.getParentId() == null
                        && memberRepo.countByTripId(t.getId()) == 0
                        && tripRepo.findByParentId(t.getId()).isEmpty())
                .map(this::toLight)
                .collect(Collectors.toList());
    }

    @GetMapping("/trips/{id}/members")
    public List<Map<String, Object>> listMembers(@PathVariable Long id) {
        return tripSvc.members(id, sec.principal());
    }

    @PostMapping("/trips/{id}/members")
    public Map<String, Object> addMember(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return tripSvc.addMember(id, body, sec.requireLogin());
    }

    @PutMapping("/trips/{id}/members/{userId}")
    public Map<String, Object> updateMemberRole(@PathVariable Long id, @PathVariable Long userId,
                                                @RequestBody Map<String, Object> body) {
        return tripSvc.updateMemberRole(id, userId, body, sec.requireLogin());
    }

    @DeleteMapping("/trips/{id}/members/{userId}")
    public Map<String, Object> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        return tripSvc.removeMember(id, userId, sec.requireLogin());
    }

    @GetMapping("/trips/{id}/trips")
    public List<Map<String, Object>> childTrips(@PathVariable Long id) {
        return tripSvc.children(id, sec.principal());
    }

    /* ---------------- 明细 CRUD ---------------- */
    @GetMapping("/trips/{id}/items")
    public List<Map<String, Object>> listItems(@PathVariable Long id) {
        tripAccess.requireView(id, sec.principal());   // 读接口补鉴权，防跨团队读取明细
        return itemRepo.findByTripId(id).stream().map(this::toItemMap).collect(Collectors.toList());
    }

    @PostMapping("/trips/{id}/items")
    public Map<String, Object> addItem(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        assertDemoTrip(id);
        TripItem it = new TripItem(id,
                str(body, "d"), str(body, "cat"), str(body, "name"),
                num(body, "amt"), str(body, "payer"), str(body, "note"));
        it.setParticipantsJson(toJson(body.get("participants")));
        TripItem saved = itemRepo.save(it);
        recompute(tripRepo.findById(id).orElseThrow());
        return toItemMap(saved);
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        TripItem it = itemRepo.findById(id).orElseThrow(() -> new NotFoundException("not found"));
        assertDemoTrip(it.getTripId());
        if (body.containsKey("d")) it.setD(str(body, "d"));
        if (body.containsKey("cat")) it.setCat(str(body, "cat"));
        if (body.containsKey("name")) it.setName(str(body, "name"));
        if (body.containsKey("amt")) it.setAmt(num(body, "amt"));
        if (body.containsKey("payer")) it.setPayer(str(body, "payer"));
        if (body.containsKey("participants")) it.setParticipantsJson(toJson(body.get("participants")));
        if (body.containsKey("note")) it.setNote(str(body, "note"));
        TripItem saved = itemRepo.save(it);
        recompute(tripRepo.findById(it.getTripId()).orElseThrow());
        return toItemMap(saved);
    }

    @DeleteMapping("/items/{id}")
    public Map<String, Object> deleteItem(@PathVariable Long id) {
        TripItem it = itemRepo.findById(id).orElseThrow(() -> new NotFoundException("not found"));
        Long tid = it.getTripId();
        assertDemoTrip(tid);
        itemRepo.deleteById(id);
        if (tripRepo.existsById(tid)) recompute(tripRepo.findById(tid).orElseThrow());
        return Map.of("ok", true);
    }

    /** 需求7：演示账号操作的 trip 必须属于演示团队 */
    private void assertDemoTrip(Long tripId) {
        assertTripWritable(tripId);
    }

    /** V3：演示作用域 + 活动编辑权双重校验（明细/行程写操作统一入口） */
    private void assertTripWritable(Long tripId) {
        Long demoT = demoTeamId();
        Trip t = tripRepo.findById(tripId).orElseThrow(() -> new NotFoundException("not found"));
        if (demoT != null && !demoT.equals(t.getTeamId())) {
            throw new ForbiddenException("演示账号只能操作演示团队的行程");
        }
        if (t.getParentId() != null) assertTripEditable(t.getParentId());
    }

    @PutMapping("/trips/{id}/recompute")
    public Map<String, Object> recomputeEndpoint(@PathVariable Long id) {
        recompute(tripRepo.findById(id).orElseThrow());
        return toLight(tripRepo.findById(id).orElseThrow());
    }

    /** AA 分摊结算：每笔由付款人代付、按 participants 均摊（空则行程全体同行人员），返回每人净额与最小转账清单 */
    @GetMapping("/trips/{id}/settle")
    public Map<String, Object> settle(@PathVariable Long id) {
        class Party { String name; BigDecimal bal; Party(String n, BigDecimal b) { name = n; bal = b; } }
        Trip t = tripAccess.requireView(id, sec.principal());   // 读接口补鉴权，AA 账单含金额与付款人，严禁越权读取
        List<String> people = parseJsonArray(t.getPeopleJson());
        List<TripItem> items = itemRepo.findByTripId(id);
        final BigDecimal ZERO = BigDecimal.ZERO;
        Map<String, BigDecimal> paid = new LinkedHashMap<>();
        Map<String, BigDecimal> share = new LinkedHashMap<>();
        for (String p : people) { paid.put(p, ZERO); share.put(p, ZERO); }
        BigDecimal total = ZERO;
        for (TripItem it : items) {
            BigDecimal amt = it.getAmt() == null ? ZERO : it.getAmt();
            if (amt.compareTo(ZERO) <= 0) continue;
            total = total.add(amt);
            String payer = it.getPayer();
            List<String> parts = parseJsonArray(it.getParticipantsJson());
            if (parts.isEmpty()) parts = new ArrayList<>(people);
            if (parts.isEmpty() && payer != null && !payer.isEmpty()) parts = new ArrayList<>(List.of(payer));
            if (payer != null && !payer.isEmpty()) {
                paid.putIfAbsent(payer, ZERO);
                paid.put(payer, paid.get(payer).add(amt));
            }
            int k = parts.size();
            if (k == 0) continue;
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
        List<Map<String, Object>> peopleOut = new ArrayList<>();
        List<Party> bal = new ArrayList<>();
        for (String p : paid.keySet()) {
            BigDecimal pai = paid.get(p);
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

    /* ---------------- 导入 / 导出 ---------------- */
    @PostMapping(value = "/import/xlsx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importXlsx(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) throws Exception {
        sec.requireLogin();
        if (demoTeamId() != null) {
            throw new ForbiddenException("演示账号不可使用全局导入（会清空真实数据）");
        }
        // 全局导入会清空全部真实数据，仅授权「数据维护」（MANAGE_TRIP）者可用
        sec.require(Permission.MANAGE_TRIP);
        Map<String, Object> r = importSvc.importXlsx(file.getInputStream(), dryRun);
        return r;
    }

    @GetMapping("/export/xlsx")
    public ResponseEntity<byte[]> exportXlsx() throws Exception {
        byte[] data = exportSvc.exportXlsx();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"travel-export.xlsx\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    /* ---------------- AI 行程规划 ---------------- */
    @PostMapping(value = "/ai/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> aiPlan(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String requirement = str(body, "requirement");
        if (requirement.isBlank()) return Map.of("ok", false, "error", "请填写行程需求");
        try {
            String plan = aiSvc.plan(requirement, body.get("context") == null ? "" : body.get("context").toString());
            return Map.of("ok", true, "plan", plan);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping(value = "/ai/save-draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveDraft(@RequestBody Map<String, Object> body) {
        Trip t = new Trip();
        t.setSheet(str(body, "title"));
        t.setTitle(str(body, "title"));
        t.setDateText(str(body, "date"));
        t.setYear(body.get("year") == null ? null : (Integer) body.get("year"));
        t.setStatus("plan");
        t.setPeopleJson(toJson(body.get("people")));
        t.setN(body.get("n") == null ? 0 : (Integer) body.get("n"));
        t.setCitiesJson(toJson(body.get("cities")));
        t.setNotesJson(toJson(body.get("notes")));
        Trip saved = tripRepo.save(t);
        return toLight(saved);
    }

    /* ---------------- 工具 ---------------- */
    private void recompute(Trip t) {
        List<TripItem> items = itemRepo.findByTripId(t.getId());
        double sum = items.stream().filter(i -> i.getAmt() != null).mapToDouble(i -> i.getAmt().doubleValue()).sum();
        t.setTotal(t.getStatus() != null && t.getStatus().equals("done") ? round2(sum) : null);
        t.setAvg(t.getStatus() != null && t.getStatus().equals("done") && t.getN() != null && t.getN() > 0 ? round2(sum / t.getN()) : null);
        t.setUpdatedAt(LocalDateTime.now());
        tripRepo.save(t);
    }

    private Map<String, Object> toLight(Trip t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("sheet", t.getSheet());
        m.put("title", t.getTitle());
        m.put("date", t.getDateText());
        m.put("year", t.getYear());
        m.put("status", t.getStatus());
        m.put("people", parseJsonArray(t.getPeopleJson()));
        m.put("n", t.getN());
        m.put("cities", parseJsonArray(t.getCitiesJson()));
        m.put("notes", parseJsonArray(t.getNotesJson()));
        m.put("total", t.getTotal());
        m.put("avg", t.getAvg());
        m.put("teamId", t.getTeamId());
        m.put("parentId", t.getParentId());
        return m;
    }

    private Map<String, Object> toItemMap(TripItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("tripId", it.getTripId());
        m.put("d", it.getD());
        m.put("cat", it.getCat());
        m.put("name", it.getName());
        m.put("amt", it.getAmt() == null ? 0 : it.getAmt().doubleValue());
        m.put("payer", it.getPayer());
        m.put("participants", parseJsonArray(it.getParticipantsJson()));
        m.put("note", it.getNote());
        return m;
    }

    private Trip fromBody(Map<String, Object> body) {
        Trip t = new Trip();
        applyBody(t, body);
        return t;
    }

    private void applyBody(Trip t, Map<String, Object> body) {
        if (body.containsKey("sheet")) t.setSheet(str(body, "sheet"));
        if (body.containsKey("title")) t.setTitle(str(body, "title"));
        if (body.containsKey("date")) t.setDateText(str(body, "date"));
        if (body.containsKey("year")) t.setYear(body.get("year") == null ? null : ((Number) body.get("year")).intValue());
        if (body.containsKey("status")) t.setStatus(str(body, "status"));
        if (body.containsKey("people")) t.setPeopleJson(toJson(body.get("people")));
        if (body.containsKey("n")) t.setN(body.get("n") == null ? 0 : ((Number) body.get("n")).intValue());
        if (body.containsKey("cities")) t.setCitiesJson(toJson(body.get("cities")));
        if (body.containsKey("notes")) t.setNotesJson(toJson(body.get("notes")));
        if (body.containsKey("parentId")) t.setParentId(longOrNull(body.get("parentId")));
        if (body.containsKey("teamId")) t.setTeamId(longOrNull(body.get("teamId")));
    }

    /** 合并后：行程归属顶层行程时的写权限校验（无 parentId 则跳过，沿用团队级逻辑） */
    private void assertTripEditable(Long parentId) {
        if (parentId == null) return;
        String u = sec.principal();
        if (!tripAccess.canEdit(parentId, u)) {
            throw new ForbiddenException("无权编辑该行程内的子行程，需编辑者及以上角色");
        }
    }

    /** 合并后：是否顶层活动（容器）——有成员或有子行程即视为活动，否则为叶子行程 */
    private boolean isActivityTrip(Long id) {
        return memberRepo.countByTripId(id) > 0 || !tripRepo.findByParentId(id).isEmpty();
    }

    private static Long longOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number num) return num.longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equals(s) || "0".equals(s)) return null;
        try { return Long.valueOf(s); } catch (Exception e) { return null; }
    }

    private List<String> parseJsonArray(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        try {
            List<String> r = new ArrayList<>();
            for (var n : om.readTree(s)) r.add(n.asText());
            return r;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private String toJson(Object o) {
        try { return om.writeValueAsString(o == null ? new ArrayList<>() : o); }
        catch (Exception e) { return "[]"; }
    }

    private static String str(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static BigDecimal num(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (v == null || v.toString().isBlank()) return null;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
