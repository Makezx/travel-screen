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
import com.laofei.travel.web.ForbiddenException;
import com.laofei.travel.web.NotFoundException;
import com.laofei.travel.web.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TripAccessService#requireWritable} 单元测试（JUnit 5 + Mockito，不启动 Spring 容器）。
 *
 * <p><b>为什么要有这个测试</b>：B5（写侧越权）修复过两次——
 * <ol>
 *   <li>第一版只补了「顶层行程校验自身编辑权」，却用「全局 MANAGE_TRIP」兜底；
 *       而预置角色 EDITOR 自带 MANAGE_TRIP（见 {@link Permission#defaultsFor}），
 *       演示账号与团队成员默认都是 EDITOR ⇒ 兜底等于没修，任何 EDITOR 仍能跨团队写任意行程。</li>
 *   <li>第二版去掉 MANAGE_TRIP 兜底，改为「活动角色 + 创建人」，全局放行只保留给系统管理员。</li>
 * </ol>
 * 这两个口径都很容易在后续重构中被"顺手加回 MANAGE_TRIP"而静默回退，故用测试锁死。
 */
class TripAccessServiceTest {

    private TripRepository tripRepo;
    private TripMemberRepository memberRepo;
    private AppUserRepository userRepo;
    private PermissionService permSvc;
    private TripAccessService access;

    @BeforeEach
    void setUp() {
        tripRepo = mock(TripRepository.class);
        memberRepo = mock(TripMemberRepository.class);
        userRepo = mock(AppUserRepository.class);
        permSvc = mock(PermissionService.class);
        access = new TripAccessService(tripRepo, memberRepo, userRepo, permSvc);
        // 默认：不是系统管理员，也没有任何全局权限
        when(permSvc.has(anyString(), ArgumentMatchers.any())).thenReturn(false);
    }

    /* ---------------- 用例构造辅助 ---------------- */

    private static Team team(long id, String name) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private static Trip trip(long id, Long parentId, Long teamId, String createdBy) {
        Trip t = new Trip();
        t.setId(id);
        t.setParentId(parentId);
        t.setTeamId(teamId);
        t.setCreatedBy(createdBy);
        return t;
    }

    /** 用户属于 teamId 团队 */
    private void user(String username, long userId, Long teamId) {
        AppUser u = new AppUser();
        u.setId(userId);
        u.setUsername(username);
        u.setEnabled(true);
        if (teamId != null) u.getTeams().add(team(teamId, "T" + teamId));
        when(userRepo.findByUsername(username)).thenReturn(Optional.of(u));
    }

    private void member(long tripId, long userId, String role) {
        TripMember m = new TripMember();
        m.setRole(role);
        when(memberRepo.findByTripIdAndUserId(eq(tripId), eq(userId))).thenReturn(Optional.of(m));
    }

    private void noMember(long tripId, long userId) {
        when(memberRepo.findByTripIdAndUserId(eq(tripId), eq(userId))).thenReturn(Optional.empty());
    }

    /* ---------------- B5 核心 ---------------- */

    @Test
    @DisplayName("B5：未登录写行程 → 401 Unauthorized")
    void requireWritable_anonymous_throwsUnauthorized() {
        when(tripRepo.findById(10L)).thenReturn(Optional.of(trip(10L, null, 1L, "boss@travel.cn")));
        assertThrows(UnauthorizedException.class, () -> access.requireWritable(10L, null));
    }

    @Test
    @DisplayName("B5：非成员、非同团队、非创建人写顶层行程 → 403（修复前完全不校验）")
    void requireWritable_strangerTopLevel_forbidden() {
        when(tripRepo.findById(10L)).thenReturn(Optional.of(trip(10L, null, 1L, "boss@travel.cn")));
        user("stranger@travel.cn", 9L, 2L);
        noMember(10L, 9L);
        assertThrows(ForbiddenException.class, () -> access.requireWritable(10L, "stranger@travel.cn"));
    }

    @Test
    @DisplayName("B5 回归点：仅持有全局 MANAGE_TRIP（EDITOR 角色自带）不足以跨团队写 —— 必须仍 403")
    void requireWritable_globalManageTripOnly_stillForbidden() {
        when(tripRepo.findById(10L)).thenReturn(Optional.of(trip(10L, null, 1L, "boss@travel.cn")));
        user("editor@travel.cn", 9L, 2L);
        noMember(10L, 9L);
        when(permSvc.has("editor@travel.cn", Permission.MANAGE_TRIP)).thenReturn(true);
        // 关键断言：MANAGE_TRIP 不是通行证，否则 EDITOR 等于全员越权
        assertThrows(ForbiddenException.class, () -> access.requireWritable(10L, "editor@travel.cn"));
    }

    @Test
    @DisplayName("B5：同团队但只是 MEMBER（无成员记录）→ 只读，写操作 403")
    void requireWritable_sameTeamMember_forbidden() {
        when(tripRepo.findById(10L)).thenReturn(Optional.of(trip(10L, null, 1L, "boss@travel.cn")));
        user("mate@travel.cn", 9L, 1L);
        noMember(10L, 9L);
        assertThrows(ForbiddenException.class, () -> access.requireWritable(10L, "mate@travel.cn"));
    }

    /* ---------------- 放行路径 ---------------- */

    @Test
    @DisplayName("顶层行程成员 EDITOR → 可写")
    void requireWritable_topLevelEditor_allowed() {
        when(tripRepo.findById(10L)).thenReturn(Optional.of(trip(10L, null, 1L, "boss@travel.cn")));
        user("ed@travel.cn", 9L, 1L);
        member(10L, 9L, TripRole.EDITOR);
        assertDoesNotThrow(() -> access.requireWritable(10L, "ed@travel.cn"));
    }

    @Test
    @DisplayName("子行程：权限沿用父层 —— 父层 EDITOR 可写子行程")
    void requireWritable_childInheritsParentRole() {
        when(tripRepo.findById(20L)).thenReturn(Optional.of(trip(20L, 10L, 1L, "boss@travel.cn")));
        when(tripRepo.findById(10L)).thenReturn(Optional.of(trip(10L, null, 1L, "boss@travel.cn")));
        user("ed@travel.cn", 9L, 1L);
        member(10L, 9L, TripRole.EDITOR);
        assertDoesNotThrow(() -> access.requireWritable(20L, "ed@travel.cn"));
    }

    @Test
    @DisplayName("创建人可写自己建的独立行程（未被任何活动收录时也不至于改不了）")
    void requireWritable_creatorOfStandalone_allowed() {
        when(tripRepo.findById(30L)).thenReturn(Optional.of(trip(30L, null, 1L, "author@travel.cn")));
        user("author@travel.cn", 9L, 1L);
        noMember(30L, 9L);
        assertDoesNotThrow(() -> access.requireWritable(30L, "author@travel.cn"));
    }

    @Test
    @DisplayName("系统管理员（拥有 MANAGE_TEAM）不受成员限制，可写任意行程")
    void requireWritable_sysAdmin_allowed() {
        when(tripRepo.findById(10L)).thenReturn(Optional.of(trip(10L, null, 1L, "boss@travel.cn")));
        user("admin2@travel.cn", 9L, 3L);
        noMember(10L, 9L);
        when(permSvc.has("admin2@travel.cn", Permission.MANAGE_TEAM)).thenReturn(true);
        assertDoesNotThrow(() -> access.requireWritable(10L, "admin2@travel.cn"));
    }

    @Test
    @DisplayName("行程不存在 → 404，且不暴露任何权限信息")
    void requireWritable_missingTrip_notFound() {
        when(tripRepo.findById(anyLong())).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> access.requireWritable(999L, "ed@travel.cn"));
    }
}
