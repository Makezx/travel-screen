package com.laofei.travel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 一笔消费明细（原来 Excel 里的一行）。
 * 这是「类 Excel 网格维护」的主要编辑对象。
 */
@Entity
@Table(name = "trip_item", indexes = {
        @Index(name = "idx_item_trip", columnList = "trip_id")
})
@Getter
@Setter
@NoArgsConstructor
public class TripItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    /** 日期文本，例如「2023.8.6」 */
    @Column(name = "d")
    private String d;

    /** 类别：吃 / 住 / 行 / 门票 / 其他 */
    @Column(name = "cat")
    private String cat;

    /** 项目名 */
    @Column(name = "item_name")
    private String name;

    /** 金额 */
    @Column(name = "amt", precision = 12, scale = 2)
    private BigDecimal amt;

    /** 付款人 */
    @Column(name = "payer")
    private String payer;

    /** 备注 */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** 本笔参与分摊的人员（JSON 数组）；空 = 按行程全体同行人员均摊 */
    @Column(name = "participants_json", columnDefinition = "TEXT")
    private String participantsJson = "[]";

    public TripItem(Long tripId, String d, String cat, String name, BigDecimal amt, String payer, String note) {
        this.tripId = tripId;
        this.d = d;
        this.cat = cat;
        this.name = name;
        this.amt = amt;
        this.payer = payer;
        this.note = note;
    }
}
