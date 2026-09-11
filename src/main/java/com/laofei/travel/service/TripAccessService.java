package com.laofei.travel.service;

import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Permission;
import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripMember;
import com.laofei.travel.model.TripRole;
import com.laofei.travel.repository.AppUserRepository;
import com.laofei.travel.repository.TripMemberRepository;
import com.laofei.travel.repository.TripRepository;
import com.laofei.travel.web.ForbiddenException;
import com.laofei.travel.web.NotFoundException;
import com.laofei.travel.web.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 行程（活动/大行程）级访问判定。合并后：顶层 Trip（parent_id=null）即「活动」，
 * 其成员与角色在 trip_member 中按行程粒度定义；子行程继承父层权限。
 * <p>
 * 判定顺序：
 * <ol>
 *   <li>系统管理员（文件式 admin 或拥有 MANAGE_TEAM / MANAGE_USER）→ 全部放行。</li>
 *   <li>行程成员 → 按 TripRole 四档判定。</li>
 *   <li>非成员但同属行程所属团队 → 视为 MEMBER（可看、可登记本人），保证团队内可见性。</li>
 *   <li>其余 → 拒绝。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripAccessService {

    private final TripRepository tripRepo;
    private final TripMemberRepository memberRepo;
    private final AppUserRepository userRepo;
    private final PermissionService permSvc;

    /** 当前用户在某顶层行程的有效角色；非成员（但同团队）返回 MEMBER；无任何关系返回 null */
    public String effectiveRole(Long tripId, String username) {
        if (username == null) return null;
        Trip t = tripRepo.findById(tripId).orElse(null);
        if (t == null) return null;

        AppUser u = userRepo.findByUsername(username).orElse(null);
        if (u == null) {
            // 文件式超级管理员不在 DB 用户表内，按系统管理员处理
            return isSysAdmin(username) ? TripRole.OWNER : null;
        }
        Optional<TripMember> m = memberRepo.findByTripIdAndUserId(tripId, u.getId());
        if (m.isPresent()) return m.get().getRole();

        // 非成员：同团队则视为 MEMBER
        boolean sameTeam = t.getTeamId() != null
                && u.getTeams().stream().anyMatch(tm -> tm.getId().equals(t.getTeamId()));
        return sameTeam ? TripRole.MEMBER : null;
    }

    public boolean canView(Long tripId, String username) {
        return roleGranted(tripId, username, r -> true);
    }

    /** 可编辑活动内行程 / 明细 */
    public boolean canEdit(Long tripId, String username) {
        return roleGranted(tripId, username, TripRole::canEditData);
    }

    /** 可管理成员与角色、关闭/删除活动 */
    public boolean canManage(Long tripId, String username) {
        return roleGranted(tripId, username, TripRole::canManageMember);
    }

    /** MEMBER 的细粒度写权限：登记本人 / AA 记账 */
    public boolean canRecord(Long tripId, String username) {
        return roleGranted(tripId, username, r -> true);
    }

    /**
     * 用户是否具备「数据维护」能力 —— 不要求全局 MANAGE_TRIP，
     * 只要在任一顶层行程中是 EDITOR / CO_OWNER / OWNER 即可维护该行程的子行程明细。
     * 系统管理员（文件 admin 或 MANAGE_TEAM / MANAGE_USER）恒为 true。
     */
    public boolean canMaintainAny(String username) {
        if (username == null) return false;
        if (isSysAdmin(username)) return true;
        return !editableTripIds(username).isEmpty();
    }

    /** 用户可编辑的顶层行程 id 列表（含系统管理员可见的全部顶层行程） */
    public Set<Long> editableTripIds(String username) {
        if (username == null) return Set.of();
        boolean admin = isSysAdmin(username);
        Long uid = userIdOf(username);
        Set<Long> out = new LinkedHashSet<>();
        for (Trip t : tripRepo.findAll()) {
            if (admin || (uid != null && TripRole.canEditData(effectiveRole(t.getId(), username)))) {
                out.add(t.getId());
            }
        }
        return out;
    }

    /* ---------------- 强制校验（失败抛异常） ---------------- */

    public Trip requireView(Long tripId, String username) {
        requireLogin(username);
        Trip t = tripRepo.findById(tripId).orElseThrow(() -> new NotFoundException("行程不存在"));
        if (!canView(tripId, username)) throw new ForbiddenException("无权查看该行程");
        return t;
    }

    public Trip requireEdit(Long tripId, String username) {
        Trip t = requireView(tripId, username);
        if (!canEdit(tripId, username)) throw new ForbiddenException("无权编辑该行程，需编辑者及以上角色");
        return t;
    }

    public Trip requireManage(Long tripId, String username) {
        Trip t = requireView(tripId, username);
        if (!canManage(tripId, username)) throw new ForbiddenException("无权管理该行程成员，需创建人或辅助 Owner");
        return t;
    }

    /**
     * 写操作（明细 / 照片 / 删除等）统一校验 —— 修复 B5 越权。
     * <ul>
     *   <li>子行程（parentId 非 null）→ 沿用父层（活动）的编辑权；</li>
     *   <li>顶层行程（parentId 为 null）→ 校验<b>该行程自身</b>的编辑权。</li>
     * </ul>
     * 修复前：顶层行程<b>完全不校验</b>，任何登录用户都能跨团队给别人的行程加明细、传照片。
     * <p>
     * ⚠️ 这里<b>不能</b>用「全局 MANAGE_TRIP」兜底：预置角色 EDITOR 自带 MANAGE_TRIP
     * （见 {@link Permission#defaultsFor}），而演示账号、团队成员默认都是 EDITOR，
     * 一旦放行等于 B5 白修 —— 任何 EDITOR 仍能跨团队写任意行程。
     * 因此全局放行只保留给<b>系统管理员</b>（文件式 admin，或拥有 MANAGE_TEAM / MANAGE_USER），
     * 该判定已内置在 {@link #canEdit} → {@link #isSysAdmin}。
     * <p>
     * 另补「创建人」放行：未挂活动的独立行程没有成员表记录，非成员同团队只能落到 MEMBER（只读），
     * 若不看 createdBy，创建者反而改不了自己刚建的行程。
     */
    public void requireWritable(Long tripId, String username) {
        if (username == null) throw new UnauthorizedException();
        Trip t = tripRepo.findById(tripId).orElseThrow(() -> new NotFoundException("行程不存在"));
        Long targetId = t.getParentId() != null ? t.getParentId() : t.getId();
        if (canEdit(targetId, username)) return;                 // 活动角色（含系统管理员全放行）
        if (username.equals(t.getCreatedBy())) return;           // 自己创建的行程
        throw new ForbiddenException(t.getParentId() != null
                ? "无权编辑该行程内的子行程，需编辑者及以上角色"
                : "无权编辑该行程，需编辑者及以上角色");
    }

    /* ---------------- 内部 ---------------- */

    private void requireLogin(String username) {
        if (username == null) throw new UnauthorizedException();
    }

    private boolean roleGranted(Long tripId, String username, java.util.function.Predicate<String> test) {
        if (username == null) return false;
        if (isSysAdmin(username)) return true;
        String role = effectiveRole(tripId, username);
        return role != null && test.test(role);
    }

    /** 系统管理员：文件式 admin，或拥有 MANAGE_TEAM / MANAGE_USER 权限 */
    private boolean isSysAdmin(String username) {
        return permSvc.has(username, Permission.MANAGE_TEAM) || permSvc.has(username, Permission.MANAGE_USER);
    }

    /** 用户 id；文件式 admin 返回 null */
    public Long userIdOf(String username) {
        return userRepo.findByUsername(username).map(AppUser::getId).orElse(null);
    }
}
