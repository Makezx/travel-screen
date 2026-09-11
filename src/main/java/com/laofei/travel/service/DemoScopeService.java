package com.laofei.travel.service;

import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Team;
import com.laofei.travel.model.Trip;
import com.laofei.travel.repository.AppUserRepository;
import com.laofei.travel.repository.TeamRepository;
import com.laofei.travel.web.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示作用域判定：演示账号只能操作「演示工作区」团队内的数据。
 *
 * <p>B14：原先 {@code ApiController} 与 {@code PhotoController} 各自按邮箱字符串
 * {@code "demo@travel.cn"} 硬编码判定，改一次演示账号的邮箱，整套演示隔离就会静默失效。
 * 现在统一读 DB 标记 {@code app_user.is_demo} 与 {@code team.is_demo}，
 * 由 {@code BootstrapService} 启动时按 DEMO_USER / DEMO_TEAM 常量幂等回填。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DemoScopeService {

    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;

    /**
     * 是否演示账号。文件式超级管理员不在 DB 用户表内，恒为 false。
     */
    public boolean isDemoUser(String username) {
        if (username == null) return false;
        return userRepo.findByUsername(username).map(AppUser::isDemo).orElse(false);
    }

    /**
     * 演示账号可操作的团队 id；非演示账号返回 null（表示不受演示作用域限制）。
     * 演示账号存在但演示团队缺失时同样返回 null —— 与历史行为保持一致。
     */
    public Long demoTeamId(String username) {
        if (!isDemoUser(username)) return null;
        return teamRepo.findFirstByIsDemoTrue().map(Team::getId).orElse(null);
    }

    /** 演示账号作用域校验：只能操作演示团队内的行程 */
    public void assertInDemoScope(Trip t, String username) {
        Long demoT = demoTeamId(username);
        if (demoT != null && !demoT.equals(t.getTeamId())) {
            throw new ForbiddenException("演示账号只能操作演示团队的行程");
        }
    }
}
