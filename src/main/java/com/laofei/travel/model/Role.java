package com.laofei.travel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 角色：权限码以 JSON 数组字符串存储（如 ["VIEW_SCREEN","MANAGE_TRIP"]）。
 * 用户与角色多对多。
 */
@Entity
@Table(name = "app_role", indexes = {@Index(name = "idx_role_code", columnList = "code")})
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色编码（唯一）：SUPER_ADMIN / ADMIN / EDITOR / VIEWER */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    /** 显示名；列名 role_name 避开保留字 name */
    @Column(name = "role_name", nullable = false, length = 80)
    private String name;

    /** 权限码 JSON 数组 */
    @Column(name = "permissions", nullable = false, columnDefinition = "TEXT")
    private String permissions;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    /** 解析权限码集合 */
    public Set<String> permissionSet() {
        return Permission.fromJson(permissions);
    }
}
