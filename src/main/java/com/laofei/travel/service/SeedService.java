package com.laofei.travel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripItem;
import com.laofei.travel.repository.TripItemRepository;
import com.laofei.travel.repository.TripRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.math.BigDecimal;

/**
 * 首次启动（库空）时用 seed-trips.json 灌库。
 * 原始 49 表 Excel 可用项目里的 Python 工具（data/build2.py）重新生成该 JSON 后覆盖 resources/data/seed-trips.json。
 * @Order(1) 保证在 BootstrapService(@Order(2)) 之前灌库，便于回填 team_id。
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class SeedService implements CommandLineRunner {

    private final TripRepository tripRepo;
    private final TripItemRepository itemRepo;
    private final ObjectMapper om;

    @Override
    public void run(String... args) throws Exception {
        if (tripRepo.count() > 0) return;
        try (InputStream in = new ClassPathResource("data/seed-trips.json").getInputStream()) {
            JsonNode root = om.readTree(in);
            int tN = 0, iN = 0;
            for (JsonNode t : root) {
                Trip trip = new Trip();
                trip.setSheet(t.path("sheet").asText());
                trip.setTitle(t.path("title").asText());
                trip.setDateText(t.path("date").asText());
                trip.setYear(t.path("year").asInt());
                trip.setStatus(t.path("status").asText("done"));
                trip.setPeopleJson(t.path("people").toString());
                trip.setN(t.path("n").asInt());
                trip.setCitiesJson(t.path("cities").toString());
                trip.setNotesJson(t.path("notes").toString());
                if (trip.getStatus().equals("done")) {
                    double sum = 0;
                    JsonNode items = t.path("items");
                    for (JsonNode it : items) sum += it.path("amt").asDouble();
                    trip.setTotal(sum);
                    trip.setAvg(trip.getN() > 0 ? sum / trip.getN() : 0);
                }
                Trip saved = tripRepo.save(trip);
                for (JsonNode it : t.path("items")) {
                    TripItem item = new TripItem(saved.getId(),
                            it.path("d").asText(),
                            it.path("cat").asText(),
                            it.path("name").asText(),
                            it.path("amt").isNumber() ? new BigDecimal(it.path("amt").asText()) : null,
                            it.path("payer").asText(),
                            it.path("note").asText());
                    itemRepo.save(item);
                    iN++;
                }
                tN++;
            }
            System.out.println("[seed] 已灌入 " + tN + " 个行程、" + iN + " 条明细。");
        } catch (Exception e) {
            System.err.println("[seed] 种子数据加载失败：" + e.getMessage());
        }
    }
}
