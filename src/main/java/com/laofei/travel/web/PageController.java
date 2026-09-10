package com.laofei.travel.web;

import com.laofei.travel.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面路由：受 AuthFilter 保护（除 /login 与 / 外都需要会话）。
 * 静态 HTML 放在 resources/static/ 下。
 * 页面级权限：/admin 需 MANAGE_TRIP；/detail、/ai 登录即可（AuthFilter 已保证）。
 */
@Controller
public class PageController {

    private final SecuritySupport sec;
    private final com.laofei.travel.service.TripAccessService tripAccess;

    public PageController(SecuritySupport sec, com.laofei.travel.service.TripAccessService tripAccess) {
        this.sec = sec;
        this.tripAccess = tripAccess;
    }

    @GetMapping("/")
    public String home() {
        return "forward:/index.html";
    }

    @GetMapping("/detail")
    public String detail() {
        return "forward:/detail.html";
    }

    @GetMapping("/admin")
    public String admin(HttpServletRequest req) {
        // V3：全局 MANAGE_TRIP 或「任一活动中为 EDITOR 及以上」均可进入数据维护
        //（行程级权限由 TripAccessService 判定，接口层再做细粒度校验）
        String username = sec.principal();
        boolean allowed = false;
        try {
            sec.require(Permission.MANAGE_TRIP);
            allowed = true;
        } catch (Exception ignored) {
            allowed = tripAccess.canMaintainAny(username);
        }
        if (!allowed) return "redirect:/";
        return "forward:/admin.html";
    }

    @GetMapping("/ai")
    public String ai() {
        return "forward:/ai.html";
    }

    @GetMapping("/profile")
    public String profile() {
        return "forward:/profile.html";
    }

    @GetMapping("/login")
    public String login() {
        return "forward:/login.html";
    }
}
