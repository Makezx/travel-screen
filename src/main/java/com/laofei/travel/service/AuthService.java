package com.laofei.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话与凭证管理：移植原 Node 版的能力
 * - 凭据：PBKDF2 加盐哈希，存 travel-auth.json（不在代码库）
 * - 会话：HMAC-SHA256 签名、无状态、HttpOnly Cookie，默认 12h
 * - 限流：同 IP 连错 5 次锁 10 分钟
 */
@Service
public class AuthService {

    private final ObjectMapper om = new ObjectMapper();
    private final SecureRandom rnd = new SecureRandom();

    @Value("${app.auth-file}")
    private String authFile;
    @Value("${app.secret-file}")
    private String secretFile;
    @Value("${app.admin-user}")
    private String adminUser;
    @Value("${app.admin-pass}")
    private String adminPass;
    @Value("${app.session-hours}")
    private int sessionHours;

    private byte[] secret;
    private String user;
    private byte[] salt;
    private byte[] hash;

    // ip -> {fails, until}
    private final ConcurrentHashMap<String, long[]> fails = new ConcurrentHashMap<>();

    @PostConstruct
    void init() throws Exception {
        loadSecret();
        loadOrCreateAuth();
    }

    private void loadSecret() throws Exception {
        File f = new File(secretFile);
        if (!f.exists()) {
            byte[] b = new byte[32];
            rnd.nextBytes(b);
            Files.writeString(f.toPath(), Base64.getEncoder().encodeToString(b));
            f.setReadable(false, false);
            f.setReadable(true, true);
        }
        secret = Base64.getDecoder().decode(Files.readString(f.toPath()).trim());
    }

    @SuppressWarnings("unchecked")
    private void loadOrCreateAuth() throws Exception {
        File f = new File(authFile);
        if (f.exists()) {
            Map<String, String> m = om.readValue(f, Map.class);
            user = m.get("user");
            salt = Base64.getDecoder().decode(m.get("salt"));
            hash = Base64.getDecoder().decode(m.get("hash"));
        } else {
            // 首次启动用环境变量默认账号密码生成凭据
            byte[] s = new byte[16];
            rnd.nextBytes(s);
            byte[] h = pbkdf2(adminPass, s);
            Map<String, String> m = Map.of(
                    "user", adminUser,
                    "salt", Base64.getEncoder().encodeToString(s),
                    "hash", Base64.getEncoder().encodeToString(h));
            om.writerWithDefaultPrettyPrinter().writeValue(f, m);
            f.setReadable(false, false);
            f.setReadable(true, true);
            user = adminUser;
            salt = s;
            hash = h;
            System.out.println("[auth] 已用默认账号生成 travel-auth.json，请尽快改密码（环境变量 ADMIN_USER/ADMIN_PASS 或管理页）。");
        }
    }

    private byte[] pbkdf2(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 100_000, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    public boolean verifyPassword(String password) {
        try {
            byte[] got = pbkdf2(password, salt);
            if (got.length != hash.length) return false;
            int r = 0;
            for (int i = 0; i < got.length; i++) r |= got[i] ^ hash[i];
            return r == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean checkUser(String u) {
        return user != null && user.equals(u);
    }

    public boolean login(String u, String p) {
        return checkUser(u) && verifyPassword(p);
    }

    public boolean locked(String ip) {
        long[] v = fails.get(ip);
        return v != null && v[1] > Instant.now().toEpochMilli();
    }

    public void markFail(String ip) {
        long[] v = fails.computeIfAbsent(ip, k -> new long[]{0, 0});
        v[0] += 1;
        if (v[0] >= 5) {
            v[1] = Instant.now().plusMillis(10 * 60_000L).toEpochMilli();
            v[0] = 0;
        }
    }

    public void clearFail(String ip) {
        fails.remove(ip);
    }

    private String b64u(byte[] b) {
        return Base64.getUrlEncoder().encodeToString(b);
    }

    private String hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
        return b64u(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    public String makeToken() {
        try {
            long exp = Instant.now().plusSeconds(sessionHours * 3600L).getEpochSecond();
            String e = b64u(String.valueOf(exp).getBytes(StandardCharsets.UTF_8));
            return e + "." + hmac(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 签发内嵌 username 的无状态 token：base64(exp|username) + "." + HMAC。
     * 端点无需查库即可解析主体（principal）。
     */
    public String issueToken(String username) {
        try {
            long exp = Instant.now().plusSeconds(sessionHours * 3600L).getEpochSecond();
            String payload = b64u((exp + "|" + username).getBytes(StandardCharsets.UTF_8));
            return payload + "." + hmac(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 从 token 解析用户名（无效/缺字段返回 null） */
    public String usernameOf(String token) {
        if (token == null || token.indexOf('.') < 0) return null;
        try {
            String payload = token.split("\\.", 2)[0];
            String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            return parts.length >= 2 ? parts[1] : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 文件式超级管理员账号名 */
    public String getAdminUser() {
        return adminUser;
    }

    public boolean checkToken(String token) {
        if (token == null || token.indexOf('.') < 0) return false;
        try {
            String[] parts = token.split("\\.", 2);
            String decoded = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            long exp = Long.parseLong(decoded.split("\\|", 2)[0]);
            if (Instant.now().getEpochSecond() > exp) return false;
            return hmac(parts[0]).equals(parts[1]);
        } catch (Exception e) {
            return false;
        }
    }

    public int getSessionHours() {
        return sessionHours;
    }

    /** 供管理页改密码使用 */
    public void setPassword(String newUser, String newPass) throws Exception {
        byte[] s = new byte[16];
        rnd.nextBytes(s);
        byte[] h = pbkdf2(newPass, s);
        Map<String, String> m = Map.of(
                "user", newUser,
                "salt", Base64.getEncoder().encodeToString(s),
                "hash", Base64.getEncoder().encodeToString(h));
        File f = new File(authFile);
        om.writerWithDefaultPrettyPrinter().writeValue(f, m);
        f.setReadable(false, false);
        f.setReadable(true, true);
        user = newUser;
        salt = s;
        hash = h;
    }
}
