package com.wengu.backend.review;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/** 临时探针：打印 java-fsrs 的默认卡状态与四种评分的调度结果（写教程取真实数值） */
class FsrsProbeTest {

    @Test
    void probe() {
        Instant now = Instant.parse("2026-10-24T10:00:00Z");
        Card empty = Card.builder().build();
        System.out.printf("EMPTY state=%s step=%s due=%s stab=%s diff=%s%n",
                empty.getState(), empty.getStep(), empty.getDue(),
                empty.getStability(), empty.getDifficulty());

        Scheduler scheduler = Scheduler.builder().build();
        for (Rating r : Rating.values()) {
            var res = scheduler.reviewCard(Card.builder().build(), r, now);
            System.out.printf("RATING %d:%s -> state=%s due=%s stab=%.4f diff=%.4f%n",
                    r.getValue(), r, res.card().getState(), res.card().getDue(),
                    res.card().getStability(), res.card().getDifficulty());
        }
    }
}
