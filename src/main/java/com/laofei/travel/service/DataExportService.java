package com.laofei.travel.service;

import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripItem;
import com.laofei.travel.repository.TripItemRepository;
import com.laofei.travel.repository.TripRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * 导出 xlsx（标准扁平格式，可与导入互转，方便像 Excel 一样维护与备份）。
 * 两个表：行程（元数据） + 明细（逐笔消费）。
 */
@Service
@RequiredArgsConstructor
public class DataExportService {

    private final TripRepository tripRepo;
    private final TripItemRepository itemRepo;

    public byte[] exportXlsx() throws Exception {
        List<Trip> trips = tripRepo.findAll();
        Workbook wb = new XSSFWorkbook();

        // 行程表
        Sheet ts = wb.createSheet("行程");
        String[] th = {"标题", "分表名", "日期", "年份", "状态", "人数", "人员", "城市", "辣评"};
        Row hr = ts.createRow(0);
        for (int i = 0; i < th.length; i++) hr.createCell(i).setCellValue(th[i]);
        int r = 1;
        for (Trip t : trips) {
            Row row = ts.createRow(r++);
            row.createCell(0).setCellValue(nullTo(t.getTitle()));
            row.createCell(1).setCellValue(nullTo(t.getSheet()));
            row.createCell(2).setCellValue(nullTo(t.getDateText()));
            row.createCell(3).setCellValue(t.getYear() == null ? "" : String.valueOf(t.getYear()));
            row.createCell(4).setCellValue(nullTo(t.getStatus()));
            row.createCell(5).setCellValue(t.getN() == null ? 0 : t.getN());
            row.createCell(6).setCellValue(joinJson(t.getPeopleJson()));
            row.createCell(7).setCellValue(joinJson(t.getCitiesJson()));
            row.createCell(8).setCellValue(joinJson(t.getNotesJson()));
        }
        for (int i = 0; i < th.length; i++) ts.autoSizeColumn(i);

        // 明细表
        Sheet is = wb.createSheet("明细");
        String[] ih = {"行程标题", "日期", "类别", "项目", "金额", "付款人", "备注"};
        Row ihr = is.createRow(0);
        for (int i = 0; i < ih.length; i++) ihr.createCell(i).setCellValue(ih[i]);
        int ir = 1;
        for (Trip t : trips) {
            for (TripItem it : itemRepo.findByTripId(t.getId())) {
                Row row = is.createRow(ir++);
                row.createCell(0).setCellValue(nullTo(t.getTitle()));
                row.createCell(1).setCellValue(nullTo(it.getD()));
                row.createCell(2).setCellValue(nullTo(it.getCat()));
                row.createCell(3).setCellValue(nullTo(it.getName()));
                Cell c = row.createCell(4);
                if (it.getAmt() != null) c.setCellValue(it.getAmt().doubleValue());
                row.createCell(5).setCellValue(nullTo(it.getPayer()));
                row.createCell(6).setCellValue(nullTo(it.getNote()));
            }
        }
        for (int i = 0; i < ih.length; i++) is.autoSizeColumn(i);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    private static String nullTo(String s) { return s == null ? "" : s; }

    private static String joinJson(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            StringBuilder sb = new StringBuilder();
            for (var n : new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)) {
                if (sb.length() > 0) sb.append("、");
                sb.append(n.asText());
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
