package com.laofei.travel.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 行程（活动/大行程）内角色常量与授予链。
 * <p>
 * 合并后：顶层 Trip（parent_id=null）即「活动/大行程」，其成员角色在此定义；
 * 子行程（parent_id 非 null）继承父层的成员与权限。
 * <p>
 * 四档角色：
 * <ul>
 *   <li>OWNER 创建人 —— 全部权限，可管理成员/角色、关闭删除活动；不可自降</li>
 *   <li>CO_OWNER 辅助 Owner —— 与 Owner 对等代管，可管成员/角色/编辑；仅 OWNER 可授予</li>
 *   <li>EDITOR 编辑者 —— 增删改活动内行程/明细；不可管成员、不可删活动、不可授权他人</li>
 *   <li>MEMBER 普通参与者 —— 查看；可选「登记本人/AA」写权限</li>
 * </ul>
 */
public final class TripRole {

    public static final String OWNER = "OWNER";
    public static final String CO_OWNER = "CO_OWNER";
    public static final String EDITOR = "EDITOR";
    public static final String MEMBER = "MEMBER";

    public static final Set<String> ALL = new LinkedHashSet<>(List.of(OWNER, CO_OWNER, EDITOR, MEMBER));

    private TripRole() {
    }

    public static boolean valid(String r) {
        return r != null && ALL.contains(r);
    }

    public static boolean isManager(String r) {
        return OWNER.equals(r) || CO_OWNER.equals(r);
    }

    /** 是否可编辑活动内行程/明细 */
    public static boolean canEditData(String r) {
        return OWNER.equals(r) || CO_OWNER.equals(r) || EDITOR.equals(r);
    }

    /** 是否可管理成员与角色 */
    public static boolean canManageMember(String r) {
        return isManager(r);
    }

    /**
     * 授予链校验：actor 能否把 target 设为 newRole。
     * 规则：
     * <ul>
     *   <li>EDITOR/MEMBER 一律不可授权</li>
     *   <li>CO_OWNER 只能授予 EDITOR / MEMBER</li>
     *   <li>OWNER 可授予 CO_OWNER / EDITOR / MEMBER</li>
     *   <li>不允许授予 OWNER（所有权不可转让，只能由系统转移）</li>
     * </ul>
     */
    public static boolean canGrant(String actorRole, String newRole) {
        if (!valid(newRole) || OWNER.equals(newRole)) return false;
        if (OWNER.equals(actorRole)) return true;
        if (CO_OWNER.equals(actorRole)) return EDITOR.equals(newRole) || MEMBER.equals(newRole);
        return false;
    }

    /**
     * 降级/移除保护：actor 能否改动 target 的角色（改角色或移除成员）。
     * OWNER 不可被改动；CO_OWNER 仅 OWNER 可改动；其余管理者可改 EDITOR/MEMBER。
     */
    public static boolean canModifyMember(String actorRole, String targetRole) {
        if (OWNER.equals(targetRole)) return false;              // Owner 不可被降级/移除
        if (CO_OWNER.equals(targetRole)) return OWNER.equals(actorRole);
        return isManager(actorRole);
    }
}
