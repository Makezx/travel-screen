package com.laofei.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laofei.travel.model.Activity;
import com.laofei.travel.model.ActivityMember;
import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Permission;
import com.laofei.travel.model.Role;
import com.laofei.travel.model.SchemaMigration;
import com.laofei.travel.model.Team;
import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripItem;
import com.laofei.travel.model.TripMember;
import com.laofei.travel.repository.ActivityMemberRepository;
import com.laofei.travel.repository.ActivityRepository;
import com.laofei.travel.repository.AppUserRepository;
import com.laofei.travel.repository.RoleRepository;
import com.laofei.travel.repository.SchemaMigrationRepository;
import com.laofei.travel.repository.TeamRepository;
import com.laofei.travel.repository.TripItemRepository;
import com.laofei.travel.repository.TripMemberRepository;
import com.laofei.travel.repository.TripRepository;
import com.laofei.travel.service.TripService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动幂等引导：确保种子团队「老废物乐园」、回填历史行程 team_id、预置 4 个角色。
 * 需求7：额外种子「示例·演示」团队 + 8 条脱敏示例行程 + demo@travel.cn 演示账号(EDITOR)。
 * 通过 @Order(2) 保证在 SeedService(@Order(1)) 灌库之后执行。
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class BootstrapService implements CommandLineRunner {

    private static final String SEED_TEAM = "老废物乐园";
    private static final String DEMO_TEAM = "示例·演示";
    private static final String DEMO_USER = "demo@travel.cn";

    private final TeamRepository teamRepo;
    private final RoleRepository roleRepo;
    private final TripRepository tripRepo;
    private final TripItemRepository itemRepo;
    private final AppUserRepository userRepo;
    private final PasswordService pwd;
    private final TripService tripSvc;
    private final TripMemberRepository tripMemberRepo;
    private final RouteService routeSvc;
    private final ActivityRepository actRepo;
    private final ActivityMemberRepository actMemberRepo;
    private final SchemaMigrationRepository migrationRepo;
    @PersistenceContext
    private EntityManager em;

    /** 一次性迁移的标记名（版本位）。存在即跳过，杜绝每次重启改数据。 */
    private static final String MIGRATION_V3_ACTIVITY_MERGE = "v3-activity-merge";

    /**
     * 启动回填路线时，行程之间的停顿（毫秒）——礼貌节流，避免打爆外部路线 API 的 QPS。
     * 见 app.route.backfill-delay-ms。想完全不限速就设 0。
     */
    @Value("${app.route.backfill-delay-ms:400}")
    private long backfillDelayMs;

    @Override
    public void run(String... args) {
        Team team = ensureSeedTeam();
        backfillTrips(team.getId());
        ensureRoles();
        ensureDemoWorkspace();
        if (migrationRepo.existsByName(MIGRATION_V3_ACTIVITY_MERGE)) {
            System.out.println("[bootstrap] v3 活动→行程合并迁移已执行过，跳过（避免每次重启改数据）");
        } else {
            migrateActivities();
            migrationRepo.save(new SchemaMigration(MIGRATION_V3_ACTIVITY_MERGE, Instant.now()));
        }
        System.out.println("[bootstrap] 种子团队/角色就绪：" + SEED_TEAM);
        // 路线回填：后台线程执行，避免拖慢启动；首屏由大屏读取 pathJson，缺失则退回直线
        new Thread(this::backfillRoutes, "route-backfill").start();
    }

    /**
     * 启动后回填所有行程的 pathJson（沿真实道路的密集坐标）。
     * 幂等：已有 pathJson 的跳过；单条失败不影响其余；无 Key/API 不可达则保留 null（直线兜底）。
     */
    private void backfillRoutes() {
        try {
            List<Trip> all = tripRepo.findAll();
            int done = 0, skip = 0, fail = 0;
            for (Trip t : all) {
                if (t.getPathJson() != null && !t.getPathJson().isBlank()) { skip++; continue; }
                try {
                    String json = routeSvc.bakeForTrip(t);
                    if (json != null && !json.isBlank()) {
                        t.setPathJson(json);
                        tripRepo.save(t);
                        done++;
                    } else {
                        skip++;
                    }
                } catch (Exception e) {
                    fail++;
                    System.out.println("[route-backfill] 行程#" + t.getId() + " 跳过: " + e.getMessage());
                }
                // 礼貌节流：外部路线 API 多有 QPS 限制（高德官方 ≤3/s），
                // 行程间停顿见 app.route.backfill-delay-ms，设 0 可完全不限速。
                if (backfillDelayMs > 0) {
                    try { Thread.sleep(backfillDelayMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
            System.out.println("[route-backfill] 完成：烘焙=" + done + " 跳过=" + skip + " 失败=" + fail + " 共=" + all.size());
        } catch (Exception e) {
            System.out.println("[route-backfill] 回填异常终止: " + e.getMessage());
        }
    }

    /**
     * 「活动→行程」合并的一次性数据迁移（幂等）：
     * 1) Activity 行 → 顶层 Trip（parent_id=null）；
     * 2) ActivityMember → TripMember；
     * 3) 旧 trip.activity_id → trip.parent_id（子行程挂到新顶层行程）。
     * 完成后调用 tripSvc.ensureLegacyContainers() 归集未分组行程到「历史出行」容器。
     */
    private void migrateActivities() {
        try {
            migrateActivitiesToTrips();
            Map<String, Object> r = tripSvc.ensureLegacyContainers();
            System.out.println("[bootstrap] 活动→行程合并迁移：" + r);
        } catch (Exception e) {
            System.out.println("[bootstrap] 活动→行程合并迁移跳过（" + e.getMessage() + "）");
        }
    }

    @Transactional
    protected void migrateActivitiesToTrips() {
        if (actRepo.count() == 0) return;                       // 无旧活动数据
        if (tripMemberRepo.count() > 0) {                        // 已迁移过，跳过避免重复
            System.out.println("[bootstrap] 活动→行程迁移已存在，跳过");
            return;
        }
        // 1) Activity -> 顶层 Trip
        Map<Long, Long> idMap = new LinkedHashMap<>();
        for (Activity a : actRepo.findAll()) {
            Trip t = new Trip();
            t.setTitle(a.getName());
            t.setTeamId(a.getTeamId());
            t.setDescription(a.getDescription());
            t.setStatus("done");
            t.setClosed("closed".equals(a.getStatus()));
            t.setCreatedBy(a.getCreatedBy());
            Trip saved = tripRepo.save(t);
            idMap.put(a.getId(), saved.getId());
        }
        // 2) ActivityMember -> TripMember
        for (ActivityMember m : actMemberRepo.findAll()) {
            Long newTrip = idMap.get(m.getActivityId());
            if (newTrip == null) continue;
            tripMemberRepo.save(new TripMember(newTrip, m.getUserId(), m.getRole()));
        }
        // 3) 旧 trip.activity_id -> trip.parent_id
        List<Object[]> rows = em.createNativeQuery("select id, activity_id from trip where activity_id is not null").getResultList();
        for (Object[] row : rows) {
            Long tripId = ((Number) row[0]).longValue();
            Long oldAct = ((Number) row[1]).longValue();
            Long newTrip = idMap.get(oldAct);
            if (newTrip == null) continue;
            Trip t = tripRepo.findById(tripId).orElse(null);
            if (t != null) {
                t.setParentId(newTrip);
                tripRepo.save(t);
            }
        }
        System.out.println("[bootstrap] 活动→行程迁移完成，活动数=" + idMap.size() + "，子行程映射=" + rows.size());
    }

    private Team ensureSeedTeam() {
        return teamRepo.findByName(SEED_TEAM).orElseGet(() -> {
            Team t = new Team();
            t.setName(SEED_TEAM);
            t.setDescription("历史出行归口团队（系统种子）");
            t.setDefault(true);
            Team saved = teamRepo.save(t);
            System.out.println("[bootstrap] 已创建种子团队 " + SEED_TEAM);
            return saved;
        });
    }

    /** 回填 team_id 为 null 的历史行程到种子团队 */
    private void backfillTrips(Long teamId) {
        List<Trip> nulls = tripRepo.findByTeamIdIsNull();
        if (nulls.isEmpty()) return;
        for (Trip t : nulls) t.setTeamId(teamId);
        tripRepo.saveAll(nulls);
        System.out.println("[bootstrap] 已回填 " + nulls.size() + " 条历史行程 team_id -> " + SEED_TEAM);
    }

    private void ensureRoles() {
        ensureRole("SUPER_ADMIN", "超级管理员", Permission.defaultsFor("SUPER_ADMIN"));
        ensureRole("ADMIN", "管理员", Permission.defaultsFor("ADMIN"));
        ensureRole("EDITOR", "编辑", Permission.defaultsFor("EDITOR"));
        ensureRole("VIEWER", "访客", Permission.defaultsFor("VIEWER"));
    }

    private void ensureRole(String code, String name, Set<String> perms) {
        if (roleRepo.existsByCode(code)) return;
        Role r = new Role();
        r.setCode(code);
        r.setName(name);
        r.setPermissions(Permission.toJson(perms));
        roleRepo.save(r);
        System.out.println("[bootstrap] 已创建预置角色 " + code);
    }

    // ==================== 需求7：演示工作区 ====================

    /** 确保演示团队 + 8 条脱敏示例行程 + demo 账号(EDITOR) 就绪，幂等。 */
    private void ensureDemoWorkspace() {
        Team demo = teamRepo.findByName(DEMO_TEAM).orElseGet(() -> {
            Team t = new Team();
            t.setName(DEMO_TEAM);
            t.setDescription("演示工作区 · 任何人可用 demo@travel.cn 登录在此增删改，不影响真实团队数据");
            t.setDefault(false);
            Team saved = teamRepo.save(t);
            System.out.println("[bootstrap] 已创建演示团队 " + DEMO_TEAM);
            return saved;
        });

        // 仅在演示团队完全无行程时灌入示例数据（幂等：已有则跳过）
        if (tripRepo.findByTeamId(demo.getId()).isEmpty()) {
            seedDemoTrips(demo.getId());
        }

        // demo 账号
        if (userRepo.findByUsername(DEMO_USER).isEmpty()) {
            AppUser u = new AppUser();
            u.setUsername(DEMO_USER);
            u.setDisplayName("演示账号");
            u.setPassword(pwd.hash("Demo@2026"));
            u.setEnabled(true);
            u.setMustChangePwd(false);
            u.getTeams().add(demo);
            Role editor = roleRepo.findByCode("EDITOR").orElse(null);
            if (editor != null) u.getRoles().add(editor);
            userRepo.save(u);
            System.out.println("[bootstrap] 已创建演示账号 " + DEMO_USER + " (Demo@2026, EDITOR, 归属演示团队)");
        }
    }

    private void seedDemoTrips(Long teamId) {
        // 与 buildMock() 一致的 8 条脱敏示例行程
        String[] PEOPLE = {"成员甲","成员乙","成员丙","成员丁","成员戊"};
        String[][] D = {
            {"示例 · 川渝美食线","2023.4.29","2023","done","4","成都,重庆","0,1,2,3","9600"},
            {"示例 · 江南水乡线","2023.7.13","2023","done","2","上海,杭州,苏州,南京","1,4","12800"},
            {"示例 · 云贵高原线","2023.10.19","2023","done","3","昆明,大理,丽江,贵阳","0,2","15200"},
            {"示例 · 湘桂山水线","2023.12.30","2023","done","2","长沙,桂林,南宁","3,4","8800"},
            {"示例 · 华中人文线","2024.5.1","2024","done","4","武汉,郑州","0,1,3,4","7600"},
            {"示例 · 京津双城线","2024.8.6","2024","done","3","北京,天津","1,2,4","11400"},
            {"示例 · 闽海海岸线","2024.10.2","2024","done","2","厦门,福州","0,4","6900"},
            {"示例 · 西北大环线","2026.10.1","2026","plan","6","西安,兰州,西宁","0,1,2,3,4","0"}
        };
        String[][] ITEMS = {{"交通","往返大交通"},{"住宿","示例酒店"},{"餐饮","当地餐食"},{"门票","景区门票"},{"其他","市内交通杂项"}};
        double[] R = {0.38,0.27,0.18,0.10,0.07};
        ObjectMapper om = new ObjectMapper();
        for (int i = 0; i < D.length; i++) {
            String[] d = D[i];
            boolean done = "done".equals(d[3]);
            int n = Integer.parseInt(d[4]);
            int yr = Integer.parseInt(d[2]);
            double cost = Double.parseDouble(d[7]);
            List<String> cities = List.of(d[5].split(","));
            List<String> people = new java.util.ArrayList<>();
            for (String pi : d[6].split(",")) people.add(PEOPLE[Integer.parseInt(pi.trim())]);
            Trip t = new Trip();
            t.setSheet("示例");
            t.setTitle(d[0]);
            t.setDateText(d[1]);
            t.setYear(yr);
            t.setStatus(d[3]);
            t.setN(n);
            try { t.setPeopleJson(om.writeValueAsString(people)); } catch (Exception e) { t.setPeopleJson("[]"); }
            try { t.setCitiesJson(om.writeValueAsString(cities)); } catch (Exception e) { t.setCitiesJson("[]"); }
            t.setNotesJson("[\"演示用脱敏示例数据\"]");
            t.setTeamId(teamId);
            if (done) {
                t.setTotal(Math.round(cost * 100.0) / 100.0);
                t.setAvg(n > 0 ? Math.round(cost / n * 100.0) / 100.0 : null);
            }
            Trip saved = tripRepo.save(t);
            if (done) {
                for (int k = 0; k < ITEMS.length; k++) {
                    double amt = Math.round(cost * R[k] * 100.0) / 100.0;
                    TripItem it = new TripItem(saved.getId(), "", ITEMS[k][0], ITEMS[k][1], BigDecimal.valueOf(amt), "", "示例数据");
                    itemRepo.save(it);
                }
            }
        }
        System.out.println("[bootstrap] 已灌入 8 条演示示例行程到 " + DEMO_TEAM);
    }
}
