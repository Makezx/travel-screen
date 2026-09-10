package com.laofei.travel.service;

import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Team;
import com.laofei.travel.model.Trip;
import com.laofei.travel.repository.AppUserRepository;
import com.laofei.travel.repository.TeamRepository;
import com.laofei.travel.repository.TripRepository;
import com.laofei.travel.web.BadRequestException;
import com.laofei.travel.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 团队管理：列表 / 最新团队 / 增删改 / 成员查询。
 * 删除团队时：解除用户关联（user_team），并把该团队行程的 team_id 置 null（落回最新团队兜底）。
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepo;
    private final TripRepository tripRepo;
    private final AppUserRepository userRepo;

    public List<Map<String, Object>> listTeams() {
        return listTeams(null);
    }

    /** 需求8：可传入当前用户 username，把其所属团队排前并标记 isMine（去掉"默认最新团队"语义）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTeams(String username) {
        Set<Long> mine = username == null ? Set.of()
                : userRepo.findByUsername(username).map(u -> u.getTeams().stream().map(Team::getId).collect(Collectors.toSet())).orElse(Set.of());
        return teamRepo.findAll().stream()
                .sorted((a, b) -> {
                    boolean am = mine.contains(a.getId()), bm = mine.contains(b.getId());
                    if (am != bm) return am ? -1 : 1;                 // 我的在前
                    int order = Boolean.compare(a.isDefault(), b.isDefault());
                    if (order != 0) return order;                      // 种子默认靠前
                    LocalDateTime ta = a.getCreatedAt(), tb = b.getCreatedAt();
                    if (ta == null && tb == null) return 0;
                    if (ta == null) return 1;
                    if (tb == null) return -1;
                    return ta.compareTo(tb);                           // 其余按创建升序(非最新)
                })
                .map(t -> { Map<String, Object> m = toDto(t); m.put("isMine", mine.contains(t.getId())); return m; })
                .collect(Collectors.toList());
    }

    /** 全部团队实体（V3 迁移回填用） */
    @Transactional(readOnly = true)
    public List<Team> allTeams() {
        return teamRepo.findAll();
    }

    /** 最新团队 DTO（created_at 最大）；无团队返回空 map */
    public Map<String, Object> latestTeamDto() {
        return teamRepo.findTopByOrderByCreatedAtDesc().map(this::toDto).orElse(Map.of());
    }

    public Team latestTeam() {
        return teamRepo.findTopByOrderByCreatedAtDesc().orElse(null);
    }

    /**
     * 需求8：登录用户的默认团队 = 其所属团队中 createdAt 最早（按成员资格优先，非系统"最新团队"）。
     * 用于去掉大屏"默认展示最新团队"的兜底。用户无团队→null（由调用方决定回退系统首个团队）。
     */
    @Transactional(readOnly = true)
    public Long defaultTeamIdForUser(String username) {
        if (username == null || username.isBlank()) return null;
        return userRepo.findByUsername(username)
                .map(u -> u.getTeams().stream()
                        .min(Comparator.comparing(Team::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(Team::getId).orElse(null))
                .orElse(null);
    }

    /**
     * 需求8：系统"主团队"兜底（去掉默认最新团队）。优先种子默认团队，其次按 createdAt 升序最早团队；
     * 用于无团队归属的管理员等首次加载，避免随"最新创建"漂移。
     */
    public Long primaryTeamId() {
        return teamRepo.findFirstByIsDefaultTrue()
                .map(Team::getId)
                .orElseGet(() -> teamRepo.findAll().stream()
                        .min(Comparator.comparing(Team::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(Team::getId).orElse(null));
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String name = str(body, "name");
        if (name.isBlank()) throw new BadRequestException("团队名不能为空");
        if (teamRepo.existsByName(name)) throw new BadRequestException("团队名已存在");
        Team t = new Team();
        t.setName(name);
        t.setDescription(str(body, "description"));
        t.setDefault(bool(body, "isDefault"));
        return toDto(teamRepo.save(t));
    }

    public Map<String, Object> update(Long id, Map<String, Object> body) {
        Team t = teamRepo.findById(id).orElseThrow(() -> new NotFoundException("团队不存在"));
        if (body.containsKey("name")) {
            String n = str(body, "name");
            if (!n.isBlank()) t.setName(n);
        }
        if (body.containsKey("description")) t.setDescription(str(body, "description"));
        if (body.containsKey("isDefault")) t.setDefault(bool(body, "isDefault"));
        return toDto(teamRepo.save(t));
    }

    public void delete(Long id) {
        Team t = teamRepo.findById(id).orElseThrow(() -> new NotFoundException("团队不存在"));
        // 解除用户关联
        List<AppUser> users = userRepo.findByTeams_Id(id);
        for (AppUser u : users) u.getTeams().remove(t);
        userRepo.saveAll(users);
        // 行程 team_id 置 null（落回最新团队兜底）
        List<Trip> ts = tripRepo.findByTeamId(id);
        for (Trip tr : ts) tr.setTeamId(null);
        tripRepo.saveAll(ts);
        teamRepo.delete(t);
    }

    public List<Map<String, Object>> members(Long id) {
        teamRepo.findById(id).orElseThrow(() -> new NotFoundException("团队不存在"));
        return userRepo.findByTeams_Id(id).stream().map(this::userBrief).collect(Collectors.toList());
    }

    private Map<String, Object> toDto(Team t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("isDefault", t.isDefault());
        m.put("createdAt", t.getCreatedAt());
        m.put("memberCount", userRepo.countByTeams_Id(t.getId()));
        return m;
    }

    private Map<String, Object> userBrief(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("enabled", u.isEnabled());
        return m;
    }

    static String str(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    static boolean bool(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v instanceof Boolean ? (Boolean) v : "true".equals(String.valueOf(v));
    }
}
