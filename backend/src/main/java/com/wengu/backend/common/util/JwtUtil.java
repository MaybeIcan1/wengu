// common/util/JwtUtil.java
package com.wengu.backend.common.util;

import io.jsonwebtoken.Jwts;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/** ★ 秘钥先常量，Phase 8 上云时改为环境变量注入（讲评时问你怎么改） */
public class JwtUtil {
    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "wengu-dev-secret-key-please-32-bytes!!".getBytes(StandardCharsets.UTF_8));
    private static final long TTL = Duration.ofDays(7).toMillis();

    public static String gen(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .expiration(new Date(System.currentTimeMillis() + TTL))
                .signWith(KEY)
                .compact();
    }
    /** 失败返回 null（抛出去会让拦截器写重复的401逻辑） */
    public static Long parse(String token) {
        try {
            return Long.valueOf(Jwts.parser().verifyWith(KEY).build()
                    .parseSignedClaims(token).getPayload().getSubject());
        } catch (Exception e) {
            return null;
        }
    }
}