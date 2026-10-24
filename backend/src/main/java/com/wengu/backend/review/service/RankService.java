package com.wengu.backend.review.service;

import com.wengu.backend.review.vo.RankItemVO;
import com.wengu.backend.security.entity.User;
import com.wengu.backend.security.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RankService {

    private final StringRedisTemplate redis;
    private final UserService userService;

    public List<RankItemVO> todayTop(int top) {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                .reverseRangeWithScores("wengu:rank:review:" + day, 0, Math.max(0, top - 1));
        if (tuples == null || tuples.isEmpty()) return List.of();

        List<Long> ids = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            if (t.getValue() != null) ids.add(Long.valueOf(t.getValue()));
        }
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User u : userService.listByIds(ids)) names.put(u.getId(), u.getNickname());
        }

        List<RankItemVO> out = new ArrayList<>();
        int rank = 1;
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            Long uid = t.getValue() == null ? null : Long.valueOf(t.getValue());
            out.add(new RankItemVO(rank++, uid == null ? "" : String.valueOf(uid),
                    names.getOrDefault(uid, "同学"),
                    t.getScore() == null ? 0 : t.getScore().intValue()));
        }
        return out;
    }
}
