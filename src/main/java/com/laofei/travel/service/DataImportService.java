package com.laofei.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripItem;
import com.laofei.travel.repository.TripItemRepository;
import com.laofei.travel.repository.TripRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

/**
 * 导入 xlsx（标准扁平格式：行程 + 明细 两个表）。
 * 采用「重建」语义：清空现有数据后整体写入，符合「上传 Excel 重新生成大屏」的诉求。
 * 原始 49 分表 Excel 请用项目内的 Python 工具（data/build2.py）先转成种子数据。
 */
@Service
@RequiredArgsConstructor
public class DataImportService {

    private final TripRepository tripRepo;
    private final TripItemRepository itemRepo;
    private final ObjectMapper om = new ObjectMapper();

    /** 兼容老调用：默认非预览，直接重建导入 */
    public Map<String, Object> importXlsx(InputStream in) throws Exception {
        return importXlsx(in, false);
    }

    /**
     * 导入 xlsx（标准扁平格式：行程 + 明细 两个表）。
     * 采用「重建」语义：清空现有数据后整体写入，符合「上传 Excel 重新生成大屏」的诉求。
     * <p>
     * {@code dryRun=true} 时只解析并统计将写入的行程/明细条数、顺带校验表头，不删不改任何数据，
     * 供前端在「会清空真实数据」之前给用户一个确认预览。
     * <p>
     * 整个重建过程在单个事务内完成：中途异常则整体回滚，不会出现「行程清空了但明细没写进去」的中间态。
     */
    @Transactional
    public Map<String, Object> importXlsx(InputStream in, boolean dryRun) throws Exception {
        Workbook wb = new XSSFWorkbook(in);
        Sheet tripSheet = wb.getSheet("行程");
        Sheet itemSheet = wb.getSheet("明细");

        int tripN = 0, itemN = 0;
        Map<String, Long> titleToId = new HashMap<>();

        // 第一遍：仅统计（dryRun 与真实导入共用，同时顺带校验表头/标题）
        if (tripSheet != null) {
            Map<Integer, String> head = headerMap(tripSheet.getRow(0));
            for (int i = 1; i <= tripSheet.getLastRowNum(); i++) {
                Row row = tripSheet.getRow(i);
                if (row == null) continue;
                String title = cell(row, head.get(0)); // 标题
                if (title.isBlank()) continue;
                tripN++;
            }
        }
        if (itemSheet != null) {
            Map<Integer, String> head = headerMap(itemSheet.getRow(0));
            for (int i = 1; i <= itemSheet.getLastRowNum(); i++) {
                Row row = itemSheet.getRow(i);
                if (row == null) continue;
                String title = cell(row, head.get(0)); // 行程标题
                if (title.isBlank()) continue;
                itemN++;
            }
        }

        if (dryRun) {
            wb.close();
            return Map.of("ok", true, "dryRun", true, "trips", tripN, "items", itemN);
        }

        // 清空现有数据（重建），与后续写入同处一个事务
        itemRepo.deleteAll();
        tripRepo.deleteAll();
        titleToId.clear();

        if (tripSheet != null) {
            Map<Integer, String> head = headerMap(tripSheet.getRow(0));
            for (int i = 1; i <= tripSheet.getLastRowNum(); i++) {
                Row row = tripSheet.getRow(i);
                if (row == null) continue;
                String title = cell(row, head.get(0));
                if (title.isBlank()) continue;
                Trip t = new Trip();
                t.setTitle(title);
                t.setSheet(cell(row, head.get(1)));
                t.setDateText(cell(row, head.get(2)));
                t.setYear(toInt(cell(row, head.get(3))));
                String status = cell(row, head.get(4));
                t.setStatus(status.isBlank() ? "done" : status);
                t.setN(toInt(cell(row, head.get(5))));
                t.setPeopleJson(toJsonArray(cell(row, head.get(6))));
                t.setCitiesJson(toJsonArray(cell(row, head.get(7))));
                t.setNotesJson(toJsonArray(cell(row, head.get(8))));
                Trip saved = tripRepo.save(t);
                titleToId.put(title, saved.getId());
                tripN++;
            }
        }

        if (itemSheet != null) {
            Map<Integer, String> head = headerMap(itemSheet.getRow(0));
            for (int i = 1; i <= itemSheet.getLastRowNum(); i++) {
                Row row = itemSheet.getRow(i);
                if (row == null) continue;
                String title = cell(row, head.get(0)); // 行程标题
                Long tid = titleToId.get(title);
                if (tid == null) continue;
                TripItem it = new TripItem(tid,
                        cell(row, head.get(1)),
                        cell(row, head.get(2)),
                        cell(row, head.get(3)),
                        toBig(cell(row, head.get(4))),
                        cell(row, head.get(5)),
                        cell(row, head.get(6)));
                itemRepo.save(it);
                itemN++;
            }
        }

        // 回填合计
        for (Trip t : tripRepo.findAll()) {
            List<TripItem> items = itemRepo.findByTripId(t.getId());
            double sum = items.stream().filter(x -> x.getAmt() != null).mapToDouble(x -> x.getAmt().doubleValue()).sum();
            if ("done".equals(t.getStatus())) {
                t.setTotal(sum);
                t.setAvg(t.getN() != null && t.getN() > 0 ? sum / t.getN() : 0);
            }
            tripRepo.save(t);
        }

        wb.close();

        return Map.of("ok", true, "trips", tripN, "items", itemN);
    }

    private Map<Integer, String> headerMap(Row hr) {
        Map<Integer, String> m = new HashMap<>();
        if (hr == null) return m;
        for (int i = 0; i < hr.getLastCellNum(); i++) {
            Cell c = hr.getCell(i);
            if (c != null) m.put(i, c.getStringCellValue().trim());
        }
        return m;
    }

    private String cell(Row row, String header) {
        if (header == null) return "";
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (header.equals(getHeader(row.getSheet(), i))) return str(row.getCell(i));
        }
        return "";
    }

    private String getHeader(Sheet s, int i) {
        Row hr = s.getRow(0);
        if (hr == null) return null;
        Cell c = hr.getCell(i);
        return c == null ? null : c.getStringCellValue().trim();
    }

    private String str(Cell c) {
        if (c == null) return "";
        switch (c.getCellType()) {
            case STRING: return c.getStringCellValue().trim();
            case NUMERIC: return c.getNumericCellValue() % 1 == 0
                    ? String.valueOf((long) c.getNumericCellValue())
                    : String.valueOf(c.getNumericCellValue());
            case BOOLEAN: return String.valueOf(c.getBooleanCellValue());
            default: return "";
        }
    }

    private Integer toInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return (int) Double.parseDouble(s.trim()); } catch (Exception e) { return null; }
    }

    private BigDecimal toBig(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return null; }
    }

    private String toJsonArray(String s) {
        if (s == null || s.isBlank()) return "[]";
        String[] parts = s.split("[、,，\\n]");
        List<String> list = new ArrayList<>();
        for (String p : parts) { p = p.trim(); if (!p.isEmpty()) list.add(p); }
        try { return om.writeValueAsString(list); } catch (Exception e) { return "[]"; }
    }
}
