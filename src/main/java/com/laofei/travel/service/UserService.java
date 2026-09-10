package com.laofei.travel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Role;
import com.laofei.travel.model.Team;
import com.laofei.travel.model.Trip;
import com.laofei.travel.repository.AppUserRepository;
import com.laofei.travel.repository.RoleRepository;
import com.laofei.travel.repository.TeamRepository;
import com.laofei.travel.repository.TripRepository;
import com.laofei.travel.web.BadRequestException;
import com.laofei.travel.web.NotFoundException;
import com.laofei.travel.util.PinyinUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理：增删改查、分配角色/团队、以及批量建号（来自出行人员，幂等）。
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private static final String SEED_TEAM = "老废物乐园";

    private final AppUserRepository userRepo;
    private final RoleRepository roleRepo;
    private final TeamRepository teamRepo;
    private final TeamService teamSvc;
    private final PasswordService pwd;
    private final TripRepository tripRepo;
    private final ObjectMapper om;

    public Map<String, Object> listUsers(int page, int size, String q) {
        Page<AppUser> p = (q == null || q.isBlank())
                ? userRepo.findAll(PageRequest.of(page, Math.max(1, size)))
                : userRepo.findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
                        q, q, PageRequest.of(page, Math.max(1, size)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", p.getContent().stream().map(this::toDto).collect(Collectors.toList()));
        out.put("total", p.getTotalElements());
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        return out;
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String username = str(body, "username").trim();
        if (!username.contains("@")) throw new BadRequestException("账号需含 @（格式 名字@travel.cn）");
        if (userRepo.existsByUsername(username)) throw new BadRequestException("账号已存在");
        AppUser u = new AppUser();
        u.setUsername(username);
        String display = str(body, "displayName");
        u.setDisplayName(display.isBlank() ? username.split("@", 2)[0] : display);
        String plain = str(body, "password");
        u.setPassword(pwd.hash(plain.isBlank() ? pwd.defaultPassword() : plain));
        u.setEnabled(body.containsKey("enabled") ? bool(body, "enabled") : true);
        u.setMustChangePwd(true);
        return toDto(userRepo.save(u));
    }

    public Map<String, Object> update(Long id, Map<String, Object> body) {
        AppUser u = userRepo.findById(id).orElseThrow(() -> new NotFoundException("用户不存在"));
        if (body.containsKey("displayName")) {
            String d = str(body, "displayName");
            if (!d.isBlank()) u.setDisplayName(d);
        }
        if (body.containsKey("enabled")) u.setEnabled(bool(body, "enabled"));
        if (body.containsKey("password")) {
            String np = str(body, "password");
            if (!np.isBlank()) {
                u.setPassword(pwd.hash(np));
                u.setMustChangePwd(true);
            }
        }
        return toDto(userRepo.save(u));
    }

    public void delete(Long id) {
        if (!userRepo.existsById(id)) throw new NotFoundException("用户不存在");
        userRepo.deleteById(id);
    }

    public Map<String, Object> setRoles(Long id, List<Long> roleIds) {
        AppUser u = userRepo.findById(id).orElseThrow(() -> new NotFoundException("用户不存在"));
        Set<Role> roles = new LinkedHashSet<>();
        if (roleIds != null) for (Long rid : roleIds) roleRepo.findById(rid).ifPresent(roles::add);
        u.setRoles(roles);
        return toDto(userRepo.save(u));
    }

    public Map<String, Object> setTeams(Long id, List<Long> teamIds) {
        AppUser u = userRepo.findById(id).orElseThrow(() -> new NotFoundException("用户不存在"));
        Set<Team> teams = new LinkedHashSet<>();
        if (teamIds != null) for (Long tid : teamIds) teamRepo.findById(tid).ifPresent(teams::add);
        u.setTeams(teams);
        return toDto(userRepo.save(u));
    }

    /**
     * 批量建号：扫描全部 Trip.peopleJson 去重得名字集合 → 账号 拼音@travel.cn。
     * 已存在（拼音账号）跳过；若已存在同名中文账号则就地改名为拼音账号（迁移，避免重复）；
     * 全新用户用默认密码、mustChangePwd=true、加入「老废物乐园」、不分配角色。幂等。
     */
    public Map<String, Object> batchCreateFromTrips() {
        Team team = Optional.ofNullable(teamSvc.latestTeam())
                .orElseGet(() -> teamRepo.findByName(SEED_TEAM).orElseGet(() -> {
                    Team t = new Team();
                    t.setName(SEED_TEAM);
                    t.setDescription("历史出行归口团队");
                    t.setDefault(true);
                    return teamRepo.save(t);
                }));

        Set<String> names = new LinkedHashSet<>();
        for (Trip t : tripRepo.findAll()) {
            for (String p : parsePeople(t.getPeopleJson())) {
                if (p != null && !p.isBlank()) names.add(p.trim());
            }
        }

        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String name : names) {
            String username = PinyinUtil.toPinyin(name) + "@travel.cn";
            if (username.isBlank() || !username.contains("@")) {
                skipped.add(name + "(无法生成拼音账号)");
                continue;
            }
            if (userRepo.existsByUsername(username)) {
                skipped.add(username);
                continue;
            }
            // 已存在同名（旧中文格式）账号 → 就地改名为拼音账号
            AppUser u = userRepo.findByDisplayName(name).stream()
                    .filter(x -> x.getUsername().contains("@travel.cn"))
                    .findFirst().orElse(null);
            if (u == null) {
                u = new AppUser();
                u.setDisplayName(name);
                u.setPassword(pwd.hash(pwd.defaultPassword()));
                u.setEnabled(true);
                u.setMustChangePwd(true);
            }
            u.setUsername(username);
            u.getTeams().add(team);
            created.add(userRepo.save(u).getUsername());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", names.size());
        out.put("created", created);
        out.put("skipped", skipped);
        out.put("team", team.getName());
        return out;
    }

    private Map<String, Object> toDto(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("enabled", u.isEnabled());
        m.put("mustChangePwd", u.isMustChangePwd());
        m.put("roles", u.getRoles().stream().map(Role::getCode).collect(Collectors.toList()));
        m.put("teams", u.getTeams().stream().map(Team::getId).collect(Collectors.toList()));
        m.put("createdAt", u.getCreatedAt());
        return m;
    }

    private List<String> parsePeople(String json) {
        List<String> r = new ArrayList<>();
        if (json == null || json.isBlank()) return r;
        try {
            for (JsonNode n : om.readTree(json)) r.add(n.asText());
        } catch (Exception ignored) {
            // 解析失败忽略该行
        }
        return r;
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
