package com.laofei.travel.web;

import com.laofei.travel.service.AuthService;
import com.laofei.travel.service.PermissionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 鉴权支持：从请求中解析 principal（用户名），并做登录 / 权限校验。
 * AuthFilter 已保证管理端点到达控制器时令牌有效，这里仅解析主体 + 权限判定。
 */
@Component
public class SecuritySupport {

    private final AuthService auth;
    private final PermissionService perm;

    public SecuritySupport(AuthService auth, PermissionService perm) {
        this.auth = auth;
        this.perm = perm;
    }

    private static String cookieVal(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (c.getName().equals(name)) return c.getValue();
        }
        return null;
    }

    /** 当前登录用户名；未登录 / 会话无效返回 null */
    public String principal() {
        var ra = RequestContextHolder.getRequestAttributes();
        if (!(ra instanceof ServletRequestAttributes sa)) return null;
        String token = cookieVal(sa.getRequest(), "wbtrav_session");
        if (token == null || !auth.checkToken(token)) return null;
        return auth.usernameOf(token);
    }

    /** 要求已登录，否则抛 UnauthorizedException（401） */
    public String requireLogin() {
        String u = principal();
        if (u == null) throw new UnauthorizedException();
        return u;
    }

    /** 要求已登录且拥有指定权限，否则抛 401 / 403 */
    public String require(String permission) {
        String u = requireLogin();
        perm.require(u, permission);
        return u;
    }
}
