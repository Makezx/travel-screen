package com.laofei.travel.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希服务：抽离现有 AuthService 的 PBKDF2WithHmacSHA256（10 万次迭代）算法，
 * 供 DB 用户（AppUser）复用，算法与文件式 admin 完全一致、可审计。
 * 存储格式：base64(salt) + ":" + base64(hash)。
 */
@Service
public class PasswordService {

    private static final int ITER = 100_000;
    private static final int KEYLEN = 256;
    private static final int SALT_LEN = 16;

    private final SecureRandom rnd = new SecureRandom();

    @Value("${app.default-user-pass:user123}")
    private String defaultPass;

    /** 新用户默认初始密码 */
    public String defaultPassword() {
        return defaultPass;
    }

    /** 对明文生成 saltB64:hashB64 */
    public String hash(String plain) {
        try {
            byte[] salt = new byte[SALT_LEN];
            rnd.nextBytes(salt);
            byte[] h = pbkdf2(plain, salt);
            return Base64.getEncoder().encodeToString(salt) + ":"
                    + Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new RuntimeException("密码哈希失败", e);
        }
    }

    /** 校验明文与已存储的 saltB64:hashB64 是否匹配 */
    public boolean verify(String plain, String stored) {
        if (plain == null || stored == null) return false;
        int i = stored.indexOf(':');
        if (i < 0) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(stored.substring(0, i));
            byte[] expect = Base64.getDecoder().decode(stored.substring(i + 1));
            byte[] got = pbkdf2(plain, salt);
            if (got.length != expect.length) return false;
            int r = 0;
            for (int k = 0; k < got.length; k++) r |= got[k] ^ expect[k];
            return r == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] pbkdf2(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITER, KEYLEN);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }
}
