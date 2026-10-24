package com.wengu.backend.review.service;

import com.wengu.backend.common.util.UserContext;
import com.wengu.backend.review.vo.CheckinStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class CheckinService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private final StringRedisTemplate redis;

    public CheckinStatusVO checkin() {
        Long uid = UserContext.requireUserId();
        LocalDate today = LocalDate.now();
        String key = monthKey(uid, YearMonth.from(today));
        redis.opsForValue().setBit(key, today.getDayOfMonth() - 1L, true);
        redis.expire(key, Duration.ofDays(40));   // ★ 位图 key 必须有 TTL，否则永久堆积
        return status();
    }

    public CheckinStatusVO status() {
        Long uid = UserContext.requireUserId();
        LocalDate today = LocalDate.now();
        boolean checkedToday = Boolean.TRUE.equals(
                redis.opsForValue().getBit(monthKey(uid, YearMonth.from(today)), today.getDayOfMonth() - 1L));

        int streak = 0;
        for (int i = 0; i < 366; i++) {
            LocalDate d = today.minusDays(i);
            if (i == 0 && !checkedToday) continue;   // 今天没签 → 从昨天开始算
            String k = monthKey(uid, YearMonth.from(d));
            if (Boolean.TRUE.equals(redis.opsForValue().getBit(k, d.getDayOfMonth() - 1L))) streak++;
            else break;
        }

        YearMonth ym = YearMonth.from(today);
        String mk = monthKey(uid, ym);
        int monthCount = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            if (Boolean.TRUE.equals(redis.opsForValue().getBit(mk, d - 1L))) monthCount++;
        }
        return new CheckinStatusVO(checkedToday, streak, monthCount);
    }

    private String monthKey(Long uid, YearMonth ym) {
        return "wengu:checkin:" + uid + ":" + ym.format(MONTH);
    }
}
