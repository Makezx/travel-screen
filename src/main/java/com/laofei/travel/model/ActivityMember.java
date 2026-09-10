package com.laofei.travel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// 注：ActivityMember 为「活动→行程」合并迁移期的只读数据源（见 BootstrapService），
// 其角色常量已统一为 TripRole。

/**
 * 活动成员及其活动内角色（V3）。
 * <p>
 * 角色四档：OWNER / CO_OWNER / EDITOR / MEMBER（见 ActivityRole）。
 * 授予链：仅 OWNER 可授予 CO_OWNER；OWNER 与 CO_OWNER 可授予 EDITOR；新成员默认 MEMBER。
 * <p>
 * 列名避坑：role → mem_role（MySQL 8 中 ROLE 为保留字）。
 */
@Entity
@Table(name = "activity_member",
        uniqueConstraints = @UniqueConstraint(name = "uk_activity_user", columnNames = {"activity_id", "user_id"}),
        indexes = {@Index(name = "idx_am_activity", columnList = "activity_id"),
                @Index(name = "idx_am_user", columnList = "user_id")})
@Getter
@Setter
@NoArgsConstructor
public class ActivityMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 活动内角色：OWNER / CO_OWNER / EDITOR / MEMBER */
    @Column(name = "mem_role", nullable = false, length = 24)
    private String role = TripRole.MEMBER;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    public ActivityMember(Long activityId, Long userId, String role) {
        this.activityId = activityId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = LocalDateTime.now();
    }
}
