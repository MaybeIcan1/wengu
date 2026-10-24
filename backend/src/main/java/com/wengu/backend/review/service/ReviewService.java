package com.wengu.backend.review.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.question.entity.Question;
import com.wengu.backend.question.event.QuestionDeletedEvent;
import com.wengu.backend.question.service.QuestionService;
import com.wengu.backend.review.entity.ReviewCard;
import com.wengu.backend.review.entity.ReviewLogEntity;
import com.wengu.backend.review.mapper.ReviewCardMapper;
import com.wengu.backend.review.mapper.ReviewLogMapper;
import com.wengu.backend.review.vo.RatingResultVO;
import com.wengu.backend.review.vo.ReviewSummaryVO;
import com.wengu.backend.review.vo.TodayCardVO;
import io.github.openspacedrepetition.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final Scheduler FSRS = Scheduler.builder().build();

    private final ReviewCardMapper cardMapper;
    private final ReviewLogMapper logMapper;
    private final QuestionService questionService;
    private final StringRedisTemplate redis;

    // ---------------- 复习会话：懒建卡 + 到期优先 + 随机补足 ----------------
    public List<TodayCardVO> startSession(Long categoryId, int count) {
        int n = Math.min(Math.max(count, 1), 50);
        List<Question> qs = questionService.listByCategory(categoryId);
        if (qs.isEmpty()) return List.of();

        List<Long> qids = qs.stream().map(Question::getId).toList();
        ensureCards(qids);

        List<ReviewCard> cards = cardMapper.selectList(Wrappers.<ReviewCard>lambdaQuery()
                .in(ReviewCard::getQuestionId, qids));
        LocalDateTime now = LocalDateTime.now();

        // ① 到期卡优先（due 升序）
        List<ReviewCard> due = cards.stream()
                .filter(c -> c.getDue() != null && !c.getDue().isAfter(now))
                .sorted(Comparator.comparing(ReviewCard::getDue))
                .collect(Collectors.toCollection(ArrayList::new));
        List<ReviewCard> picked = new ArrayList<>(due.subList(0, Math.min(due.size(), n)));

        // ② 不足则从未到期卡里随机补足（纯随机会让刚背熟的题重复出现，所以只做补足）
        if (picked.size() < n) {
            Set<Long> pickedIds = picked.stream().map(ReviewCard::getId).collect(Collectors.toSet());
            List<ReviewCard> rest = cards.stream()
                    .filter(c -> !pickedIds.contains(c.getId()))
                    .collect(Collectors.toCollection(ArrayList::new));
            Collections.shuffle(rest, ThreadLocalRandom.current());
            picked.addAll(rest.subList(0, Math.min(rest.size(), n - picked.size())));
        }
        return toVOs(picked);
    }

    /** 懒建卡：只为缺卡的题插入；并发撞唯一键视为已存在 */
    private void ensureCards(List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return;   // ★ MP 空集合 in 会炸
        Set<Long> exists = cardMapper.selectList(Wrappers.<ReviewCard>lambdaQuery()
                        .select(ReviewCard::getQuestionId)
                        .in(ReviewCard::getQuestionId, questionIds))
                .stream().map(ReviewCard::getQuestionId).collect(Collectors.toSet());

        for (Long qid : questionIds) {
            if (exists.contains(qid)) continue;
            ReviewCard c = new ReviewCard();
            c.setQuestionId(qid);
            c.setState(State.LEARNING.getValue());
            c.setStep(0);
            c.setDue(LocalDateTime.now());     // 新卡立即到期（本次会话即可考）
            c.setReps(0);
            c.setLapses(0);
            try {
                cardMapper.insert(c);          // userId 由 MetaObjectHandler 填充
            } catch (DuplicateKeyException ignored) {
                // 并发建卡：另一个请求已建，忽略
            }
        }
    }

    /** 题目被删除时清理卡片（事件驱动，避免与题目域循环依赖） */
    @EventListener
    public void onQuestionDeleted(QuestionDeletedEvent event) {
        cardMapper.delete(Wrappers.<ReviewCard>lambdaQuery()
                .eq(ReviewCard::getQuestionId, event.questionId()));
    }

    public ReviewSummaryVO summary() {
        Long due = cardMapper.selectCount(Wrappers.<ReviewCard>lambdaQuery()
                .le(ReviewCard::getDue, LocalDateTime.now()));
        return new ReviewSummaryVO(due == null ? 0 : due.intValue());
    }

    // ---------------- 评分调度（心脏） ----------------
    @Transactional(rollbackFor = Exception.class)
    public RatingResultVO rate(Long cardId, int rating) {
        ReviewCard c = cardMapper.selectById(cardId);
        if (c == null) throw new BizException(404, "卡片不存在");
        Rating r = toRating(rating);

        Card fsrsCard = Card.builder()
                .cardId((int) (c.getId() & 0x7fffffff))
                .state(toState(c.getState()))
                .step(c.getStep())
                .stability(c.getStability())
                .difficulty(c.getDifficulty())
                .due(toInstant(c.getDue()))
                .lastReview(toInstant(c.getLastReview()))
                .build();

        Instant now = Instant.now();
        CardAndReviewLog res = FSRS.reviewCard(fsrsCard, r, now);
        Card nc = res.card();

        int stateBefore = c.getState() == null ? State.LEARNING.getValue() : c.getState();
        LocalDateTime lastReviewBefore = c.getLastReview();

        c.setState(nc.getState().getValue());
        c.setStep(nc.getStep());
        c.setStability(nc.getStability());
        c.setDifficulty(nc.getDifficulty());
        c.setDue(toLocal(nc.getDue()));
        c.setLastReview(toLocal(nc.getLastReview()));
        c.setReps((c.getReps() == null ? 0 : c.getReps()) + 1);
        if (r == Rating.AGAIN) c.setLapses((c.getLapses() == null ? 0 : c.getLapses()) + 1);
        cardMapper.updateById(c);

        ReviewLogEntity logRow = new ReviewLogEntity();
        logRow.setCardId(c.getId());
        logRow.setQuestionId(c.getQuestionId());
        logRow.setRating(rating);
        logRow.setStateBefore(stateBefore);
        logRow.setStateAfter(nc.getState().getValue());
        logRow.setStability(nc.getStability());
        logRow.setDifficulty(nc.getDifficulty());
        logRow.setDueAfter(toLocal(nc.getDue()));
        logRow.setElapsedDays(lastReviewBefore == null ? 0
                : (int) Math.max(0, ChronoUnit.DAYS.between(lastReviewBefore, LocalDateTime.now())));
        logRow.setScheduledDays((int) Math.max(0,
                ChronoUnit.DAYS.between(LocalDateTime.now(), toLocal(nc.getDue()))));
        logRow.setReviewedAt(LocalDateTime.now());
        logMapper.insert(logRow);

        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        redis.opsForZSet().incrementScore("wengu:rank:review:" + day, String.valueOf(c.getUserId()), 1);
        redis.expire("wengu:rank:review:" + day, Duration.ofDays(3));

        return new RatingResultVO(String.valueOf(c.getId()), nc.getState().getValue(),
                c.getDue() == null ? null : c.getDue().toString(),
                nc.getStability(), nc.getDifficulty());
    }

    // ---------------- 随机问答（单题，不参与评分） ----------------
    public TodayCardVO random() {
        Question q = questionService.randomOne();
        if (q == null) return null;
        return new TodayCardVO(null, String.valueOf(q.getId()), q.getTitle(), q.getAnswerMd(), null, null);
    }

    // ---------------- 工具 ----------------
    private List<TodayCardVO> toVOs(List<ReviewCard> cards) {
        if (cards.isEmpty()) return List.of();
        List<Long> qids = cards.stream().map(ReviewCard::getQuestionId).distinct().toList();
        Map<Long, Question> qs = questionService.listByIds(qids).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        return cards.stream().map(c -> {
            Question q = qs.get(c.getQuestionId());
            return new TodayCardVO(
                    String.valueOf(c.getId()),
                    String.valueOf(c.getQuestionId()),
                    q == null ? "（题目已删除）" : q.getTitle(),
                    q == null ? "" : q.getAnswerMd(),
                    c.getState(),
                    c.getDue() == null ? null : c.getDue().toString());
        }).toList();
    }

    private Rating toRating(int v) {
        return switch (v) {
            case 1 -> Rating.AGAIN;      // 忘记
            case 2 -> Rating.HARD;       // 有点印象
            case 3 -> Rating.GOOD;       // 十拿九稳
            case 4 -> Rating.EASY;       // 完全背诵
            default -> throw new BizException(400, "评分必须是 1~4");
        };
    }

    private State toState(Integer v) {
        if (v == null) return State.LEARNING;
        return switch (v) {
            case 2 -> State.REVIEW;
            case 3 -> State.RELEARNING;
            default -> State.LEARNING;
        };
    }

    private Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant();
    }

    private LocalDateTime toLocal(Instant t) {
        return t == null ? null : LocalDateTime.ofInstant(t, ZoneId.systemDefault());
    }
}
