package com.laofei.travel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Permission;
import com.laofei.travel.model.Role;
import com.laofei.travel.model.Team;
import com.laofei.travel.repository.AppUserRepository;
import com.laofei.travel.service.TripAccessService;
import com.laofei.travel.service.AuthService;
import com.laofei.travel.service.PasswordService;
import com.laofei.travel.service.TeamService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
public class AuthController {

    private final AuthService auth;
    private final ObjectMapper om;
    private final AppUserRepository userRepo;
    private final PasswordService pwd;
    private final SecuritySupport sec;
    private final TeamService teamSvc;
    private final TripAccessService tripAccess;

    public AuthController(AuthService auth, ObjectMapper om, AppUserRepository userRepo, PasswordService pwd, SecuritySupport sec, TeamService teamSvc, TripAccessService tripAccess) {
        this.auth = auth;
        this.om = om;
        this.userRepo = userRepo;
        this.pwd = pwd;
        this.sec = sec;
        this.teamSvc = teamSvc;
        this.tripAccess = tripAccess;
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private void setSessionCookie(HttpServletResponse res, String token) {
        Cookie c = new Cookie("wbtrav_session", token);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(auth.getSessionHours() * 3600);
        c.setAttribute("SameSite", "Lax");
        res.addCookie(c);
    }

    @PostMapping(value = "/api/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void loginJson(@RequestBody Map<String, String> body, HttpServletRequest req, HttpServletResponse res) throws IOException {
        handleLogin(body.get("user"), body.get("pass"), req, res);
    }

    @PostMapping(value = "/api/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void loginForm(@RequestParam("user") String user, @RequestParam("pass") String pass, HttpServletRequest req, HttpServletResponse res) throws IOException {
        handleLogin(user, pass, req, res);
    }

    private void handleLogin(String user, String pass, HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json;charset=UTF-8");
        String ip = clientIp(req);
        if (auth.locked(ip)) {
            res.setStatus(429);
            res.getWriter().write("{\"ok\":false,\"err\":2}");
            return;
        }
        // ① 文件式超级管理员
        if (user != null && pass != null && auth.login(user, pass)) {
            auth.clearFail(ip);
            setSessionCookie(res, auth.issueToken(auth.getAdminUser()));
            res.setStatus(200);
            res.getWriter().write("{\"ok\":true,\"role\":\"SUPER_ADMIN\"}");
            return;
        }
        // ② DB 用户
        if (user != null) {
            Optional<AppUser> ou = userRepo.findByUsername(user);
            if (ou.isPresent()) {
                AppUser u = ou.get();
                if (u.isEnabled() && pwd.verify(pass, u.getPassword())) {
                    auth.clearFail(ip);
                    setSessionCookie(res, auth.issueToken(u.getUsername()));
                    res.setStatus(200);
                    res.getWriter().write("{\"ok\":true,\"mustChangePwd\":" + u.isMustChangePwd() + "}");
                    return;
                }
            }
        }
        auth.markFail(ip);
        res.setStatus(401);
        res.getWriter().write("{\"ok\":false,\"err\":1}");
    }

    /** 登录用户改密：文件 admin 与 DB 用户走不同分支 */
    @PostMapping(value = "/api/change-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void changePassword(@RequestBody Map<String, String> body, HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json;charset=UTF-8");
        String username = new SecuritySupport(auth, null).principal();
        if (username == null) {
            res.setStatus(401);
            res.getWriter().write("{\"ok\":false,\"error\":\"未登录\"}");
            return;
        }
        String old = body.get("old");
        String nw = body.get("new");
        if (nw == null || nw.isBlank()) {
            res.setStatus(400);
            res.getWriter().write("{\"ok\":false,\"error\":\"新密码不能为空\"}");
            return;
        }
        try {
            if (auth.checkUser(username)) {
                // 文件 admin：校验原密码后写入
                if (old == null || !auth.verifyPassword(old)) {
                    res.setStatus(400);
                    res.getWriter().write("{\"ok\":false,\"error\":\"原密码错误\"}");
                    return;
                }
                auth.setPassword(username, nw);
            } else {
                AppUser u = userRepo.findByUsername(username).orElse(null);
                if (u == null) {
                    res.setStatus(401);
                    res.getWriter().write("{\"ok\":false,\"error\":\"用户不存在\"}");
                    return;
                }
                if (!pwd.verify(old, u.getPassword())) {
                    res.setStatus(400);
                    res.getWriter().write("{\"ok\":false,\"error\":\"原密码错误\"}");
                    return;
                }
                u.setPassword(pwd.hash(nw));
                u.setMustChangePwd(false);
                userRepo.save(u);
            }
            res.setStatus(200);
            res.getWriter().write("{\"ok\":true}");
        } catch (Exception e) {
            res.setStatus(400);
            res.getWriter().write("{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /** 当前登录用户信息（含角色 / 权限 / 是否需改密） */
    @GetMapping("/api/me")
    @Transactional(readOnly = true)
    public Map<String, Object> me(HttpServletRequest req) {
        String username = sec.principal();
        if (username == null) throw new UnauthorizedException();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        if (auth.checkUser(username)) {
            m.put("roles", List.of("SUPER_ADMIN"));
            m.put("permissions", new ArrayList<>(Permission.ALL));
            m.put("mustChangePwd", false);
            m.put("isFileAdmin", true);
            m.put("canMaintain", true);
            m.put("editableActivityIds", tripAccess.editableTripIds(username));
            return m;
        }
        AppUser u = userRepo.findByUsername(username).orElseThrow(UnauthorizedException::new);
        m.put("displayName", u.getDisplayName());
        m.put("codename", u.getCodename());
        m.put("avatar", u.getAvatar());
        m.put("mustChangePwd", u.isMustChangePwd());
        m.put("enabled", u.isEnabled());
        List<String> roles = new ArrayList<>();
        Set<String> perms = new HashSet<>();
        for (Role r : u.getRoles()) {
            roles.add(r.getCode());
            perms.addAll(r.permissionSet());
        }
        m.put("roles", roles);
        m.put("permissions", new ArrayList<>(perms));
        // 需求8：默认团队（用于去掉"默认最新团队"，见 AuthController/TeamService）
        Long defT = teamSvc.defaultTeamIdForUser(username);
        m.put("defaultTeamId", defT);
        m.put("teamIds", u.getTeams().stream().map(Team::getId).sorted().collect(Collectors.toList()));
        // V3：活动级维护能力 —— 不强制全局 MANAGE_TRIP，活动内 EDITOR 及以上即可维护该活动数据
        m.put("canMaintain", tripAccess.canMaintainAny(username));
        m.put("editableActivityIds", tripAccess.editableTripIds(username));
        return m;
    }

    /** 需求6：登录用户维护个人资料（显示名/代号/头像）。文件 admin 不可改（无 DB 记录）。 */
    @PutMapping(value = "/api/me/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateProfile(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String username = sec.principal();
        if (username == null) throw new UnauthorizedException();
        if (auth.checkUser(username)) {
            // 文件 admin：仅允许改密码（资料维护走 /profile 里的密码区），资料字段固定返回
            return Map.of("ok", true, "note", "file-admin profile read-only");
        }
        AppUser u = userRepo.findByUsername(username).orElseThrow(UnauthorizedException::new);
        if (body.containsKey("displayName")) {
            String dn = body.get("displayName");
            if (dn != null && !dn.isBlank()) u.setDisplayName(dn.trim());
        }
        if (body.containsKey("codename")) u.setCodename(blankToNull(body.get("codename")));
        if (body.containsKey("avatar")) u.setAvatar(blankToNull(body.get("avatar")));
        userRepo.save(u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("displayName", u.getDisplayName());
        m.put("codename", u.getCodename());
        m.put("avatar", u.getAvatar());
        return m;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }

    @GetMapping("/logout")
    public void logout(HttpServletResponse res) throws IOException {
        Cookie c = new Cookie("wbtrav_session", "");
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(0);
        c.setAttribute("SameSite", "Lax");
        res.addCookie(c);
        res.sendRedirect("/login");
    }
}
