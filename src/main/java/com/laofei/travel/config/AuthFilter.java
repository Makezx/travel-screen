package com.laofei.travel.config;

import com.laofei.travel.service.AuthService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
@Order(1)
public class AuthFilter implements Filter {

    private final AuthService auth;
    private static final Set<String> STATIC_EXT = Set.of(
            ".css", ".js", ".png", ".jpg", ".jpeg", ".svg", ".ico", ".woff2", ".map", ".json");

    public AuthFilter(AuthService auth) {
        this.auth = auth;
    }

    private static String cookieVal(HttpServletRequest req, String name) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return null;
        for (Cookie c : cs) if (c.getName().equals(name)) return c.getValue();
        return null;
    }

    private boolean isStatic(String p) {
        int i = p.lastIndexOf('.');
        if (i < 0) return false;
        return STATIC_EXT.contains(p.substring(i).toLowerCase());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getServletPath();
        String method = req.getMethod();

        // 公开资源（/photos/** 为行程照片静态目录，游客在大屏要能看照片；/api/teams 需登录：团队选择仅登录后可用）
        // /api/geo 为纯地理底图（无行程隐私），公开 + 按天缓存，供游客大屏与登录用户共用
        if (path.equals("/health") || path.equals("/api/health")
                || path.equals("/login") || path.equals("/logout")
                || path.equals("/") || path.equals("/api/screen-data")
                || path.equals("/api/geo")
                || path.startsWith("/photos/")
                || (method.equals("POST") && path.equals("/api/login"))
                || isStatic(path)) {
            chain.doFilter(request, response);
            return;
        }

        String token = cookieVal(req, "wbtrav_session");
        if (auth.checkToken(token)) {
            chain.doFilter(request, response);
            return;
        }

        // 未登录
        if (path.startsWith("/api")) {
            res.setStatus(401);
            res.setContentType("application/json; charset=utf-8");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
        } else {
            res.sendRedirect("/login");
        }
    }
}
