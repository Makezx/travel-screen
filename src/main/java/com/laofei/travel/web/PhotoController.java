package com.laofei.travel.web;

import com.laofei.travel.model.Trip;
import com.laofei.travel.model.TripPhoto;
import com.laofei.travel.repository.TeamRepository;
import com.laofei.travel.repository.TripPhotoRepository;
import com.laofei.travel.repository.TripRepository;
import com.laofei.travel.service.AiPlanService;
import com.laofei.travel.service.TripAccessService;
import com.laofei.travel.web.SecuritySupport;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 行程照片：上传 / 列表 / 改元数据 / 删除 + AI 图片识别。
 * 文件落盘到 photoDir（PHOTO_DIR，默认 ./photos），经 /photos/** 匿名访问。
 */
@RestController
@RequestMapping("/api")
public class PhotoController {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final long MAX_SIZE = 10L * 1024 * 1024; // 10MB
    /** 缩略图长边上限：大屏照片墙直出原图会卡死，统一走长边 800px 的 JPEG */
    private static final int THUMB_MAX_EDGE = 800;

    private final TripPhotoRepository photoRepo;
    private final TripRepository tripRepo;
    private final TeamRepository teamRepo;
    private final TripAccessService tripAccess;
    private final SecuritySupport sec;
    private final AiPlanService aiSvc;
    private final Path photoDir;

    public PhotoController(TripPhotoRepository photoRepo, TripRepository tripRepo, TeamRepository teamRepo,
                           TripAccessService tripAccess, SecuritySupport sec, AiPlanService aiSvc,
                           Path photoDirPath) {
        this.photoRepo = photoRepo;
        this.tripRepo = tripRepo;
        this.teamRepo = teamRepo;
        this.tripAccess = tripAccess;
        this.sec = sec;
        this.aiSvc = aiSvc;
        this.photoDir = photoDirPath;
    }

    /* ---------------- 上传 ---------------- */

    @PostMapping(value = "/trips/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable Long id,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "city", required = false) String city,
                                      @RequestParam(value = "caption", required = false) String caption) throws IOException {
        assertPhotoWritable(id);
        validateImage(file);
        String fileName = UUID.randomUUID().toString().replace("-", "") + extOf(file);
        Files.createDirectories(photoDir);
        Path target = photoDir.resolve(fileName);
        file.transferTo(target);
        TripPhoto p = new TripPhoto(id, fileName, strOrNull(city), strOrNull(caption));
        // 缩略图失败不影响上传（前端 thumbUrl 缺失时回退原图），故不抛异常
        p.setThumbFileName(writeThumb(target, baseName(fileName)));
        return toMap(photoRepo.save(p));
    }

    /* ---------------- 列表 ---------------- */

    @GetMapping("/trips/{id}/photos")
    public List<Map<String, Object>> list(@PathVariable Long id) {
        String u = sec.principal();
        if (!tripAccess.canView(id, u)) throw new ForbiddenException("无权查看该行程");
        List<TripPhoto> photos = photoRepo.findByTripId(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TripPhoto p : photos) out.add(toMap(p));
        return out;
    }

    /* ---------------- 更新元数据 ---------------- */

    @PutMapping("/photos/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        TripPhoto p = photoRepo.findById(id).orElseThrow(() -> new NotFoundException("照片不存在: " + id));
        assertPhotoWritable(p.getTripId());
        if (body.containsKey("city")) p.setCity(strOrNull(body.get("city")));
        if (body.containsKey("caption")) p.setCaption(strOrNull(body.get("caption")));
        return toMap(photoRepo.save(p));
    }

    /* ---------------- 删除（删文件 + 删行） ---------------- */

    @DeleteMapping("/photos/{id}")
    public Map<String, Object> delete(@PathVariable Long id) throws IOException {
        TripPhoto p = photoRepo.findById(id).orElseThrow(() -> new NotFoundException("照片不存在: " + id));
        assertPhotoWritable(p.getTripId());
        // 文件名由服务端生成（UUID + 白名单扩展名），无路径穿越风险
        Files.deleteIfExists(photoDir.resolve(p.getFileName()));
        if (p.getThumbFileName() != null) Files.deleteIfExists(photoDir.resolve(p.getThumbFileName()));
        photoRepo.deleteById(id);
        return Map.of("ok", true);
    }

    /* ---------------- AI 图片识别 ---------------- */

    @PostMapping(value = "/ai/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> aiPhoto(@RequestParam("file") MultipartFile file) {
        try {
            validateImage(file);
            return aiSvc.analyzePhoto(file.getContentType(), file.getBytes());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "AI 识别失败" : e.getMessage());
        }
    }

    /* ---------------- 内部工具 ---------------- */

    /** 写操作权限：演示账号作用域 + 活动编辑权（与 ApiController.assertTripWritable 同一套口径） */
    private void assertPhotoWritable(Long tripId) {
        Trip t = tripRepo.findById(tripId).orElseThrow(() -> new NotFoundException("not found"));
        String principal = sec.principal();
        if (principal != null && principal.equals("demo@travel.cn")) {
            Long demoT = teamRepo.findByName("示例·演示").map(tm -> tm.getId()).orElse(null);
            if (demoT != null && !demoT.equals(t.getTeamId())) {
                throw new ForbiddenException("演示账号只能操作演示团队的行程");
            }
        }
        if (t.getParentId() != null && !tripAccess.canEdit(t.getParentId(), principal)) {
            throw new ForbiddenException("无权编辑该行程的照片，需编辑者及以上角色");
        }
    }

    /** 图片校验：非空 + Content-Type 白名单 + 大小上限 10MB */
    private void validateImage(MultipartFile f) {
        if (f == null || f.isEmpty()) throw new BadRequestException("请选择要上传的图片");
        if (f.getSize() > MAX_SIZE) throw new BadRequestException("图片不能超过 10MB");
        String ct = f.getContentType();
        if (ct == null || !ALLOWED_TYPES.contains(ct.toLowerCase())) {
            throw new BadRequestException("仅支持 jpg / png / webp / gif 图片");
        }
    }

    /** 从原文件名提取扩展名（仅白名单内，兜底按 Content-Type 推断），返回形如 ".jpg" */
    private String extOf(MultipartFile f) {
        String name = f.getOriginalFilename();
        if (name != null) {
            int i = name.lastIndexOf('.');
            if (i >= 0 && i < name.length() - 1) {
                String ext = name.substring(i).toLowerCase();
                if (".jpeg".equals(ext)) ext = ".jpg";
                if (Set.of(".jpg", ".png", ".webp", ".gif").contains(ext)) return ext;
            }
        }
        return switch (f.getContentType() == null ? "" : f.getContentType().toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    /** 生成长边 800px 的 JPEG 缩略图，返回文件名；原图本身不大、解码失败或写入失败时返回 null */
    private String writeThumb(Path src, String baseName) {
        try {
            BufferedImage img = ImageIO.read(src.toFile());
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight();
            if (w <= 0 || h <= 0) return null;
            int edge = Math.max(w, h);
            if (edge <= THUMB_MAX_EDGE) return null; // 原图不大，直接用原图，省一份存储
            double scale = (double) THUMB_MAX_EDGE / edge;
            int tw = Math.max(1, (int) Math.round(w * scale));
            int th = Math.max(1, (int) Math.round(h * scale));
            BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.drawImage(img, 0, 0, tw, th, null);
            g.dispose();
            String name = baseName + "_thumb.jpg";
            return ImageIO.write(out, "jpg", photoDir.resolve(name).toFile()) ? name : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** "abc.jpg" / "abc" -> "abc" */
    private static String baseName(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(0, i) : fileName;
    }

    private static String strOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private Map<String, Object> toMap(TripPhoto p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("tripId", p.getTripId());
        m.put("fileName", p.getFileName());
        m.put("city", p.getCity());
        m.put("caption", p.getCaption());
        m.put("url", "/photos/" + p.getFileName());
        m.put("thumbUrl", p.getThumbFileName() == null ? null : "/photos/" + p.getThumbFileName());
        m.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
        return m;
    }
}
