package com.laofei.travel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 系统用户（DB 用户，区别于文件式超级管理员 admin）。
 * 密码格式：base64(salt) + ":" + base64(hash)（PBKDF2WithHmacSHA256，同文件 admin 算法）。
 * 用户 ↔ 团队、用户 ↔ 角色 均为多对多，由 user_team / user_role 关联表承载。
 */
@Entity
@Table(name = "app_user", indexes = {@Index(name = "idx_user_username", columnList = "username")})
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录账号，格式 名字@travel.cn，全局唯一 */
    @Column(name = "username", nullable = false, unique = true, length = 120)
    private String username;

    /** 原始显示名（如 张伟） */
    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    /** 代号/昵称（可选），有代号时对外展示优先用代号 */
    @Column(name = "codename", length = 60)
    private String codename;

    /** 头像图标（预设 emoji/图标 key，可选） */
    @Column(name = "avatar", length = 24)
    private String avatar;

    /** saltB64:hashB64 */
    @Column(name = "password", nullable = false, length = 200)
    private String password;

    /** 是否启用 */
    @Column(name = "enabled")
    private boolean enabled = true;

    /** 首次登录是否强制改密 */
    @Column(name = "must_change_pwd")
    private boolean mustChangePwd = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_team",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "team_id"))
    private Set<Team> teams = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
