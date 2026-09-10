package com.laofei.travel.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 一次性迁移标记表（版本位）。
 * 用「专用标记」而不是业务数据（如 trip_member 计数）判断是否跑过迁移，
 * 避免业务数据污染判据导致迁移被永久跳过，或每次重启都改数据。
 */
@Entity
@Table(name = "schema_migration")
public class SchemaMigration {

    /** 迁移名，如 "v3-activity-merge" */
    @Id
    @Column(name = "name", length = 80)
    private String name;

    @Column(name = "applied_at")
    private Instant appliedAt;

    public SchemaMigration() {
    }

    public SchemaMigration(String name, Instant appliedAt) {
        this.name = name;
        this.appliedAt = appliedAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }
}
