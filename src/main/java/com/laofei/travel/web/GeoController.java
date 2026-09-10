package com.laofei.travel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.laofei.travel.service.ScreenDataService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 底图（中国地图边界）独立端点。
 *
 * <p>为什么单独拆出来：底图是纯地理数据，与行程/团队/活动无关，且已扩到 369 个地级市
 * （约 1MB）。以前每次 /api/screen-data 都整包重传，切一次活动就多拉 1MB 纯浪费。
 * 拆开后按天缓存，客户端只在本地缓存失效时才真正下载。
 *
 * <p>隐私：本端点刻意不返回 {@code place2adcode} —— 那张「景区/县 → 所属市」别名表
 * 由真实行程生成，对外等同泄露作者去过哪些地方。前端也从不读它。
 *
 * <p>合规：底图来源为阿里云 DataV GeoAtlas，含台湾省（710000）与南海诸岛/十段线
 * （100000_JD），符合国家版图要求。
 */
@RestController
public class GeoController {

    private final ScreenDataService screen;

    public GeoController(ScreenDataService screen) {
        this.screen = screen;
    }

    /** 公开端点（AuthFilter 已放行）：底图不含任何行程隐私数据 */
    @GetMapping(value = "/api/geo", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> geo() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(screen.publicGeo());
    }
}
