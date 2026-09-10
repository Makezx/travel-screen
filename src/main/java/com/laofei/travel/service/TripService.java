package com.laofei.travel.service;

import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Permission;
import com.laofei.travel.model.Team;
import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripMember;
import com.laofei.travel.model.TripRole;
import com.laofei.travel.repository.AppUserRepository;
import com.laofei.travel.repository.TripMemberRepository;
import com.laofei.travel.repository.TripRepository;
import com.laofei.travel.web.BadRequestException;
import com.laofei.travel.web.ForbiddenException;
import com.laofei.travel.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 顶层行程（活动/大行程）服务：列表 / 创建 / 更新 / 关闭 / 删除 / 成员与角色管理。
 * 合并后顶层 Trip（parent_id=null）即「活动」，子行程通过 parent_id 挂在其下。
 * 角色授予链与越权保护集中在 TripRole，访问判定集中在 TripAccessService。
 */
@Service
@RequiredArgsConstructor
public class TripService {

    private final TripRepository tripRepo;
    private final TripMemberRepository memberRepo;
    private final AppUserRepository userRepo;
    private final TripAccessService access;
    private final PermissionService permSvc;
    private final TeamService teamSvc;

    /* ---------------- 列表 ---------------- */

    /**
     * 可见顶层行程列表。系统管理员看全部；普通用户看「我参与的」+「我所属团队的」。
     * 每条带 myRole（当前用户角色）、childCount、totalAmount、memberCount。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String username) {
        boolean admin = isAdmin(username);
        List<Trip> all = tripRepo.findByParentIdIsNull();

        Long myId = userIdOf(username);
        List<Long> myTeamIds = myTeamIds(username);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Trip a : all) {
            boolean member = myId != null && memberRepo.findByTripIdAndUserId(a.getId(), myId).isPresent();
            boolean sameTeam = a.getTeamId() != null && myTeamIds.contains(a.getTeamId());
            if (!admin && !member && !sameTeam) continue;

            String role = access.effectiveRole(a.getId(), username);
            Map<String, Object> m = toDto(a, role);
            m.put("isMember", member);
            out.add(m);
        }
        out.sort((x, y) -> {
            int c = Boolean.compare(!(Boolean) x.get("closed"), !(Boolean) y.get("closed")); // 进行中在前
            return c != 0 ? c : y.get("id").toString().compareTo(x.get("id").toString());     // 新在前
        });
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id, String username) {
        access.requireView(id, username);
        Trip a = tripRepo.findById(id).orElseThrow(() -> new NotFoundException("行程不存在"));
        return toDto(a, access.effectiveRole(id, username));
    }

    /* ---------------- 增删改 ---------------- */

    @Transactional
    public Map<String, Object> create(Map<String, Object> body, String username) {
        if (username == null) throw new ForbiddenException("请先登录");
        String name = str(body, "name");
        if (name.isBlank()) throw new BadRequestException("活动名不能为空");

        Long teamId = body.get("teamId") == null ? null : Long.valueOf(str(body, "teamId"));
        if (teamId == null) teamId = defaultTeamIdFor(username);
        if (teamId == null) throw new BadRequestException("无法确定活动归属团队，请先创建团队");

        Trip a = new Trip();
        a.setTitle(name);
        a.setTeamId(teamId);
        a.setDescription(str(body, "description"));
        a.setStatus("done");      // 容器默认状态（前端按 closed 判定进行中/已结束）
        a.setClosed(false);       // 进行中
        a.setCreatedBy(username);
        Trip saved = tripRepo.save(a);

        // 创建人自动成为 OWNER
        Long uid = userIdOf(username);
        if (uid != null) memberRepo.save(new TripMember(saved.getId(), uid, TripRole.OWNER));

        // 可选：初始成员 [{"userId":1,"role":"EDITOR"}]
        Object init = body.get("members");
        if (init instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> raw)) continue;
                Map<String, Object> mm = new LinkedHashMap<>();
                raw.forEach((k, v) -> mm.put(String.valueOf(k), v));
                Long uid2 = mm.get("userId") == null ? null : Long.valueOf(String.valueOf(mm.get("userId")));
                if (uid2 == null || uid2.equals(uid)) continue;
                String r = String.valueOf(mm.getOrDefault("role", TripRole.MEMBER));
                if (!TripRole.valid(r) || TripRole.OWNER.equals(r)) r = TripRole.MEMBER;
                memberRepo.save(new TripMember(saved.getId(), uid2, r));
            }
        }
        return toDto(saved, TripRole.OWNER);
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> body, String username) {
        access.requireManage(id, username);
        Trip a = tripRepo.findById(id).orElseThrow(() -> new NotFoundException("行程不存在"));
        if (body.containsKey("name")) {
            String n = str(body, "name");
            if (!n.isBlank()) a.setTitle(n);
        }
        if (body.containsKey("description")) a.setDescription(str(body, "description"));
        if (body.containsKey("closed")) {
            Object c = body.get("closed");
            a.setClosed(c instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(c)));
        }
        if (body.containsKey("status")) {
            String s = str(body, "status");
            if ("open".equals(s) || "closed".equals(s) || "done".equals(s) || "plan".equals(s)) {
                // 兼容老前端可能传 open/closed；统一转成 closed 布尔
                a.setClosed("closed".equals(s));
            }
        }
        return toDto(tripRepo.save(a), access.effectiveRole(id, username));
    }

    /** 删除顶层行程：仅 OWNER 或系统管理员；子行程解除归属（变未分组），成员删除 */
    @Transactional
    public Map<String, Object> delete(Long id, String username) {
        access.requireManage(id, username);
        String role = access.effectiveRole(id, username);
        if (!isAdmin(username) && !TripRole.OWNER.equals(role)) {
            throw new ForbiddenException("仅创建人可删除活动");
        }
        List<Trip> kids = tripRepo.findByParentId(id);
        for (Trip t : kids) t.setParentId(null);
        tripRepo.saveAll(kids);
        memberRepo.deleteByTripId(id);
        tripRepo.deleteById(id);
        return Map.of("ok", true, "detachedTrips", kids.size());
    }

    /* ---------------- 成员 ---------------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(Long id, String username) {
        access.requireView(id, username);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TripMember m : memberRepo.findByTripId(id)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", m.getId());
            d.put("userId", m.getUserId());
            d.put("role", m.getRole());
            d.put("joinedAt", m.getJoinedAt());
            AppUser u = userRepo.findById(m.getUserId()).orElse(null);
            if (u != null) {
                d.put("username", u.getUsername());
                d.put("displayName", displayName(u));
                d.put("avatar", u.getAvatar());
            }
            out.add(d);
        }
        out.sort((x, y) -> Integer.compare(roleRank((String) x.get("role")), roleRank((String) y.get("role"))));
        return out;
    }

    /** 添加成员：actor 需有管理权，且只能授予其被允许的等级 */
    @Transactional
    public Map<String, Object> addMember(Long id, Map<String, Object> body, String username) {
        access.requireManage(id, username);
        String actorRole = access.effectiveRole(id, username);

        Long uid = body.get("userId") == null ? null : Long.valueOf(str(body, "userId"));
        if (uid == null) {
            String un = str(body, "username");
            if (!un.isBlank()) uid = userRepo.findByUsername(un).map(AppUser::getId).orElse(null);
        }
        if (uid == null) throw new BadRequestException("成员不存在");
        if (memberRepo.findByTripIdAndUserId(id, uid).isPresent()) {
            throw new BadRequestException("该用户已在活动内");
        }
        String newRole = String.valueOf(body.getOrDefault("role", TripRole.MEMBER));
        if (!TripRole.canGrant(actorRole, newRole)) {
            throw new ForbiddenException("无权授予该角色：" + newRole);
        }
        TripMember m = memberRepo.save(new TripMember(id, uid, newRole));
        return Map.of("ok", true, "id", m.getId(), "userId", uid, "role", newRole);
    }

    /** 修改成员角色 */
    @Transactional
    public Map<String, Object> updateMemberRole(Long id, Long userId, Map<String, Object> body, String username) {
        access.requireManage(id, username);
        String actorRole = access.effectiveRole(id, username);
        TripMember m = memberRepo.findByTripIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("该用户不是活动成员"));
        String newRole = String.valueOf(body.getOrDefault("role", TripRole.MEMBER));
        if (!TripRole.canGrant(actorRole, newRole)) throw new ForbiddenException("无权授予该角色：" + newRole);
        if (!TripRole.canModifyMember(actorRole, m.getRole())) throw new ForbiddenException("无权修改该成员角色");
        if (TripRole.OWNER.equals(m.getRole())) throw new ForbiddenException("创建人角色不可变更");
        m.setRole(newRole);
        memberRepo.save(m);
        return Map.of("ok", true, "userId", userId, "role", newRole);
    }

    /** 移除成员 */
    @Transactional
    public Map<String, Object> removeMember(Long id, Long userId, String username) {
        access.requireManage(id, username);
        String actorRole = access.effectiveRole(id, username);
        TripMember m = memberRepo.findByTripIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("该用户不是活动成员"));
        if (!TripRole.canModifyMember(actorRole, m.getRole())) throw new ForbiddenException("无权移除该成员");
        if (TripRole.OWNER.equals(m.getRole())) throw new ForbiddenException("创建人不可被移除");
        memberRepo.delete(m);
        return Map.of("ok", true);
    }

    /* ---------------- 子行程 ---------------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> children(Long id, String username) {
        access.requireView(id, username);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Trip t : tripRepo.findByParentId(id)) {
            out.add(toLeaf(t));
        }
        return out;
    }

    /* ---------------- 迁移：历史出行归集 ---------------- */

    /**
     * 为每个尚无「历史出行」容器的团队建一个顶层 Trip（活动），并把该团队下未分组的叶子行程挂进去。
     * 幂等：已有容器则复用（按团队 + 标题匹配），不重复创建；
     * 团队成员在容器内登记为 EDITOR，保持合并前"同团队即可编辑行程"的现状。
     */
    @Transactional
    public Map<String, Object> ensureLegacyContainers() {
        int created = 0, attached = 0, regMembers = 0;
        List<Team> teams = new ArrayList<>();
        teamSvc.allTeams().forEach(teams::add);
        for (Team t : teams) {
            List<Trip> top = tripRepo.findByTeamId(t.getId()).stream()
                    .filter(x -> x.getParentId() == null)
                    .filter(x -> "历史出行".equals(x.getTitle()))
                    .toList();
            Trip target = top.isEmpty() ? null : top.get(0);
            if (target == null) {
                Trip a = new Trip();
                a.setTitle("历史出行");
                a.setDescription("V3 合并：自动归集本团队历史行程");
                a.setStatus("done");
                a.setClosed(true);
                a.setTeamId(t.getId());
                a.setCreatedBy("system");
                target = tripRepo.save(a);
                created++;
            }
            // 团队成员登记 EDITOR（保持"同团队可编辑"现状）
            for (AppUser u : userRepo.findByTeams_Id(t.getId())) {
                if (memberRepo.findByTripIdAndUserId(target.getId(), u.getId()).isEmpty()) {
                    memberRepo.save(new TripMember(target.getId(), u.getId(), TripRole.EDITOR));
                    regMembers++;
                }
            }
            // 计算本团队内的容器 id 集合（自身为父的顶层行程），未分组叶子 = parentId 空且非容器
            Set<Long> containerIds = new HashSet<>();
            for (Trip x : tripRepo.findByTeamId(t.getId())) {
                if (x.getParentId() != null) containerIds.add(x.getParentId());
            }
            List<Trip> toAttach = new ArrayList<>();
            for (Trip tr : tripRepo.findByTeamId(t.getId())) {
                // 注意：必须排除容器自身，否则会把刚创建的容器挂到自己头上（自引用，parent_id 非空 → 顶层列表为空）
                // 关键：只归集「无创建人」的历史叶子（早期 seed/导入数据）。用户新建的活动必有 createdBy（见 create()），
                // 若也归进来会把新建的、尚无子行程的活动吞进「历史出行」、从活动列表消失。
                if (tr.getParentId() == null && !tr.getId().equals(target.getId())
                        && !containerIds.contains(tr.getId()) && tr.getCreatedBy() == null) {
                    tr.setParentId(target.getId());
                    toAttach.add(tr);
                }
            }
            if (!toAttach.isEmpty()) {
                tripRepo.saveAll(toAttach);
                attached += toAttach.size();
            }
        }
        return Map.of("createdContainers", created, "attachedTrips", attached, "registeredMembers", regMembers);
    }

    /* ---------------- 内部 ---------------- */

    private List<Long> myTeamIds(String username) {
        if (username == null) return List.of();
        return userRepo.findByUsername(username)
                .map(u -> u.getTeams().stream().map(Team::getId).toList())
                .orElse(List.of());
    }

    private Long defaultTeamIdFor(String username) {
        Long t = teamSvc.defaultTeamIdForUser(username);
        return t != null ? t : teamSvc.primaryTeamId();
    }

    private Long userIdOf(String username) {
        return username == null ? null : userRepo.findByUsername(username).map(AppUser::getId).orElse(null);
    }

    private boolean isAdmin(String username) {
        return username != null
                && (permSvc.has(username, Permission.MANAGE_TEAM) || permSvc.has(username, Permission.MANAGE_USER));
    }

    private Map<String, Object> toDto(Trip a, String myRole) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getTitle());                 // 兼容前端 a.name
        m.put("title", a.getTitle());
        m.put("teamId", a.getTeamId());
        m.put("description", a.getDescription());
        m.put("closed", Boolean.TRUE.equals(a.getClosed()));
        m.put("status", a.getStatus());
        m.put("createdBy", a.getCreatedBy());
        m.put("createdAt", a.getCreatedAt());
        m.put("updatedAt", a.getUpdatedAt());
        m.put("myRole", myRole);
        m.put("canEdit", myRole != null && TripRole.canEditData(myRole));
        m.put("canManage", myRole != null && TripRole.canManageMember(myRole));
        m.put("memberCount", memberRepo.countByTripId(a.getId()));
        List<Trip> kids = tripRepo.findByParentId(a.getId());
        m.put("childCount", kids.size());
        double sum = kids.stream().filter(t -> t.getTotal() != null).mapToDouble(Trip::getTotal).sum();
        m.put("totalAmount", Math.round(sum * 100.0) / 100.0);
        return m;
    }

    /** 叶子行程（子行程 / 未分组行程）的轻量 DTO，对应 ApiController.toLight 的字段 */
    private Map<String, Object> toLeaf(Trip t) {
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

    private String displayName(AppUser u) {
        if (u.getCodename() != null && !u.getCodename().isBlank()) return u.getCodename();
        return u.getDisplayName();
    }

    private int roleRank(String r) {
        return switch (r == null ? "" : r) {
            case TripRole.OWNER -> 0;
            case TripRole.CO_OWNER -> 1;
            case TripRole.EDITOR -> 2;
            default -> 3;
        };
    }

    private List<String> parseJsonArray(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        try {
            List<String> r = new ArrayList<>();
            for (var n : new com.fasterxml.jackson.databind.ObjectMapper().readTree(s)) r.add(n.asText());
            return r;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    static String str(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
