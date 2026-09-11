package com.laofei.travel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 团队：行程按 teamId 显式归属；用户与团队多对多。
 * "最新团队" = created_at 最大者。
 */
@Entity
@Table(name = "team", indexes = {@Index(name = "idx_team_name", columnList = "team_name")})
@Getter
@Setter
@NoArgsConstructor
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 团队名（唯一），例如「老废物乐园」；列名 team_name 避开保留字 name */
    @Column(name = "team_name", nullable = false, unique = true, length = 80)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    /** 是否系统种子默认团队 */
    @Column(name = "is_default")
    private boolean isDefault = false;

    /**
     * 是否「演示工作区」团队。演示账号只能操作本团队内的数据，
     * 改演示账号邮箱不会失效（B14：替代原先按 email 字符串硬编码的判定）。
     * 由 BootstrapService 启动时按 DEMO_TEAM 名称幂等回填。
     */
    @Column(name = "is_demo")
    private boolean isDemo = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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
