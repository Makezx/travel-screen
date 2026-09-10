package com.laofei.travel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一次出行（对应原来 Excel 的一个分表）。
 * people / cities / notes 以 JSON 字符串存储，避免多余的关联表，便于整体读写。
 */
@Entity
@Table(name = "trip")
@Getter
@Setter
@NoArgsConstructor
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原始分表名，例如「苏州」 */
    @Column(name = "sheet")
    private String sheet;

    /** 展示标题，例如「苏州」 */
    @Column(name = "title")
    private String title;

    /** 日期文本，例如「2023.8.6-8.8」 */
    @Column(name = "date_text")
    private String dateText;

    @Column(name = "trip_year")
    private Integer year;

    /** done=已完成；plan=计划中 */
    @Column(name = "status")
    private String status = "done";

    /** 同行人员 JSON 数组，例如 ["张伟","王芳"] */
    @Column(name = "people_json", columnDefinition = "TEXT")
    private String peopleJson = "[]";

    /** 出行人数 */
    @Column(name = "n")
    private Integer n = 0;

    /** 途径城市 JSON 数组，例如 ["杭州","苏州","杭州"] */
    @Column(name = "cities_json", columnDefinition = "TEXT")
    private String citiesJson = "[]";

    /** 辣评 / 备注 JSON 数组 */
    @Column(name = "notes_json", columnDefinition = "TEXT")
    private String notesJson = "[]";

    /** 合计金额（计划行程可为空） */
    @Column(name = "total")
    private Double total;

    /** 人均 */
    @Column(name = "trip_avg")
    private Double avg;

    /** 归属团队 id（null=未分配，聚合时落在最新团队兜底）；team_id 非保留字，可直用 */
    @Column(name = "team_id")
    private Long teamId;

    /**
     * 父行程 id（自引用；V3 合并后：null=顶层=活动/大行程，非 null=子行程）。
     * parent_id 非保留字，可直用。
     */
    @Column(name = "parent_id")
    private Long parentId;

    /** 活动/大行程说明（仅顶层有意义） */
    @Column(name = "description", length = 500)
    private String description;

    /** 是否归档/结束（仅顶层有意义；false=进行中，true=已结束；与 status 的 done/plan 区分） */
    @Column(name = "closed")
    private Boolean closed;

    /** 创建人 username（顶层活动创建人，同时为首任 OWNER） */
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
