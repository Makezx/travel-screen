package com.laofei.travel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 行程成员及其行程内角色（合并后：顶层 Trip=活动/大行程 的成员与角色）。
 * <p>
 * 角色四档：OWNER / CO_OWNER / EDITOR / MEMBER（见 TripRole）。
 * 授予链：仅 OWNER 可授予 CO_OWNER；OWNER 与 CO_OWNER 可授予 EDITOR；新成员默认 MEMBER。
 * <p>
 * 列名避坑：role → mem_role（MySQL 8 中 ROLE 为保留字）。
 */
@Entity
@Table(name = "trip_member",
        uniqueConstraints = @UniqueConstraint(name = "uk_trip_user", columnNames = {"trip_id", "user_id"}),
        indexes = {@Index(name = "idx_tm_trip", columnList = "trip_id"),
                @Index(name = "idx_tm_user", columnList = "user_id")})
@Getter
@Setter
@NoArgsConstructor
public class TripMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 行程内角色：OWNER / CO_OWNER / EDITOR / MEMBER */
    @Column(name = "mem_role", nullable = false, length = 24)
    private String role = TripRole.MEMBER;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    public TripMember(Long tripId, Long userId, String role) {
        this.tripId = tripId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = LocalDateTime.now();
    }
}
