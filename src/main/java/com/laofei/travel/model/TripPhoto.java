package com.laofei.travel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 行程照片：文件本体落在磁盘（PHOTO_DIR，默认 ./photos），
 * 这里只存元数据（文件名 / 城市 / 说明），通过 /photos/{fileName} 公开访问。
 */
@Entity
@Table(name = "trip_photo", indexes = {
        @Index(name = "idx_photo_trip", columnList = "trip_id")
})
@Getter
@Setter
@NoArgsConstructor
public class TripPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    /** 磁盘文件名（UUID + 原扩展名），访问路径 /photos/{fileName} */
    @Column(name = "file_name", nullable = false, length = 128)
    private String fileName;

    /** 缩略图文件名（长边 800px 的 JPEG）；为 null 表示未生成（原图本身不大 / 解码失败），前端回退用 url */
    @Column(name = "thumb_file_name", length = 128)
    private String thumbFileName;

    /** 城市（可空，AI 识别或手动填写） */
    @Column(name = "city")
    private String city;

    /** 说明（可空） */
    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public TripPhoto(Long tripId, String fileName, String city, String caption) {
        this.tripId = tripId;
        this.fileName = fileName;
        this.city = city;
        this.caption = caption;
    }
}
