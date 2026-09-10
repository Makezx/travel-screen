package com.laofei.travel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 活动（V3 核心）：一次可自助协作的出行计划。
 * <p>
 * 与团队（Team）的区别：团队是长期的人员归口，活动是一次具体的出行事件；
 * 行程（Trip）通过 activity_id 挂到活动下，成员角色在 activity_member 中按活动粒度定义。
 * <p>
 * 列名避坑：name → act_name（H2/MySQL 保留字），沿用项目既有约定。
 */
@Entity
@Table(name = "activity", indexes = {@Index(name = "idx_activity_team", columnList = "team_id")})
@Getter
@Setter
@NoArgsConstructor
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 活动名，例如「2026 国庆 · 川西环线」 */
    @Column(name = "act_name", nullable = false, length = 120)
    private String name;

    /** 归属团队 id（活动必须挂在某团队下） */
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "description", length = 500)
    private String description;

    /** open=进行中；closed=已结束（结束仍可看，不可再记） */
    @Column(name = "status", length = 16)
    private String status = "open";

    /** 创建人 username（同时是首任 OWNER） */
    @Column(name = "created_by", length = 120)
    private String createdBy;

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
