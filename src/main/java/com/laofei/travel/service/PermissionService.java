package com.laofei.travel.service;

import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Permission;
import com.laofei.travel.model.Role;
import com.laofei.travel.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * 粗粒度权限校验：根据 principal（用户名）解析其权限集合。
 * 文件式超级管理员（AuthService 中的 admin）拥有全部权限；DB 用户聚合其角色权限。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionService {

    private final AppUserRepository userRepo;
    private final AuthService auth;

    /** 解析某用户的权限集合 */
    public Set<String> permissionsOf(String username) {
        if (username != null && auth.checkUser(username)) {
            return new HashSet<>(Permission.ALL);
        }
        AppUser u = userRepo.findByUsername(username).orElse(null);
        if (u == null || !u.isEnabled()) return Set.of();
        Set<String> perms = new HashSet<>();
        for (Role r : u.getRoles()) {
            perms.addAll(r.permissionSet());
        }
        return perms;
    }

    public boolean has(String username, String perm) {
        return perm != null && permissionsOf(username).contains(perm);
    }

    /** 无权限时抛出 ForbiddenException（由全局异常处理映射为 403） */
    public void require(String username, String perm) {
        if (!has(username, perm)) throw new com.laofei.travel.web.ForbiddenException();
    }
}
