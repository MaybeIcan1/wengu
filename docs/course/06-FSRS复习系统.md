# 06 · FSRS 复习系统（Phase 5）

> 状态：⬜ 待开发 ｜ **v2（2026-10-24 需求变更：复习会话制 + 评分文案重构）**
> 变更点：
> ① **建卡不再发生在"新建题目"时**——题库只是仓库；用户点「开始复习」并选择分类后，才为该分类下所有题目**懒建卡**
> ② 会话选题 = **到期优先（due ≤ now）+ 随机补足**（既尊重调度，又保证每次凑够题）
> ③ 评分四档文案改为回忆质量递进：**忘记 / 有点印象 / 十拿九稳 / 完全背诵**（内部值 1~4 与 FSRS 映射不变）

---

## 🎯 本章目标与「我需要什么」

**业务需求（讲人话）**：
题库里躺着 60 道题，但你不希望"建题即复习"——**复习是一个主动动作**。你的用法应该是：

> 今天想复习「计算机网络」→ 点开始复习 → 系统把这一类题全部纳入复习体系 → 随机抽 10 道做成考题卡片 → 我看题、自测、翻面、评分（忘记/有点印象/十拿九稳/完全背诵）→ 系统按 FSRS 安排下次出现时间。

**需要**：Phase 0~4 全绿；Redis 容器在线；依赖已装：
- `io.github.open-spaced-repetition:fsrs:1.0.0`
- `spring-boot-starter-data-redis`

> 💡 为什么用官方 FSRS 库？19 个权重的公式自己写容易"能跑但调度失真"。我们只负责**状态机 + 数据落库 + 会话体验**。

## 0️⃣ 本章全景：一次复习的完整旅程

```
① 进入复习页（还没有卡！）
   选分类 + 选数量（默认 10）→ 点「开始复习」
   POST /review/session/start?categoryId=xx&count=10
      ──> 取该分类下所有题目（questionService.listByCategory）
      ──> 懒建卡：缺卡的批量补 t_card（state=LEARNING, due=now）
      ──> 选题：① 到期卡（due<=now）按 due 升序优先
                ② 不够 N 张 → 从未到期卡里随机补足
      ──> 返回本次会话的卡片列表（含题面+答案）
② 看题 → 点"显示答案"（纯前端翻转）
③ 评分（1 忘记 / 2 有点印象 / 3 十拿九稳 / 4 完全背诵）
   POST /review/{cardId}/rating?rating=3
      ──> 载入 t_card → 映射 fsrs.Card
      ──> Scheduler.reviewCard(card, GOOD, now)      ← 算法
      ──> 回写 t_card（state/step/stability/difficulty/due/last_review/reps）
      ──> 追加 t_review_log
      ──> ZINCRBY 今日复习榜 +1
      ──> 前端本地队列推进（不重新请求列表）
④ 签到（可选）：BitMap SETBIT → 连续天数从今天往回逐位扫描
⑤ 榜单：ZREVRANGE 今日复习榜 → 补昵称
```

**分层定位表**：

| 层 | 产出 | 本层结束系统能干什么 |
|---|---|---|
| 1️⃣ 规格 | t_card / t_review_log + Redis key + FSRS 数值/文案对照 | 数据形状钉死 |
| 2️⃣ 数据层 | 两个 Mapper + QuestionService.listByCategory | 数据层可自证 |
| 3️⃣ 业务 | ReviewService（懒建卡/会话选题/评分）、CheckinService、RankService | 评分→调度→落库可跑 |
| 4️⃣ 接口 | /review/session/start、/review/{id}/rating、/checkin、/rank/today、/review/summary | Apifox 全流程可走 |
| 5️⃣ 前端 | 复习页（选分类→开始→卡片流→评分）+ 侧栏入口/徽标 | 每天能用的闭环 |

## 1️⃣ 规格定义层（结束时：数据形状钉死）

**FSRS 真实行为**（用探针实测 `fsrs-1.0.0` 默认参数）：

| 评分（新文案） | 内部值 | 结果状态 | 下次到期 | stability | difficulty |
|---|---|---|---|---|---|
| 新卡（初始） | — | LEARNING | 立即 | null | null |
| **忘记** | 1 (AGAIN) | LEARNING | +1 分钟 | 0.2172 | 7.0114 |
| **有点印象** | 2 (HARD) | LEARNING | +5.5 分钟 | 1.1771 | 6.2431 |
| **十拿九稳** | 3 (GOOD) | LEARNING | +10 分钟 | 3.2602 | 4.8846 |
| **完全背诵** | 4 (EASY) | **REVIEW**（毕业） | **+19 天** | 16.1507 | 2.4824 |

> ★ 文案为什么重要：FSRS 的 Hard/Good/Easy 描述的是**回忆质量**，不是题目难度。直译成"困难/良好/简单"会让用户以为在评价题目，导致评分失真（评分失真 → 调度失真）。**「有点印象 / 十拿九稳 / 完全背诵」是用户能稳定自评的语义。**

**DDL（Navicat 执行）**：

```sql
CREATE TABLE t_card (
  id            BIGINT       NOT NULL,
  user_id       BIGINT       NOT NULL,
  question_id   BIGINT       NOT NULL,
  state         TINYINT      NOT NULL DEFAULT 1 COMMENT '1学习 2复习 3重学',
  step          INT          NOT NULL DEFAULT 0,
  stability     DOUBLE       DEFAULT NULL,
  difficulty    DOUBLE       DEFAULT NULL,
  due           DATETIME     NOT NULL COMMENT '下次到期（会话选题依据）',
  last_review   DATETIME     DEFAULT NULL,
  reps          INT          NOT NULL DEFAULT 0,
  lapses        INT          NOT NULL DEFAULT 0,
  created_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_question (user_id, question_id),
  KEY idx_user_due (user_id, due)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复习卡（懒创建：开始复习时才建）';

CREATE TABLE t_review_log (
  id             BIGINT   NOT NULL,
  user_id        BIGINT   NOT NULL,
  card_id        BIGINT   NOT NULL,
  question_id    BIGINT   NOT NULL,
  rating         TINYINT  NOT NULL COMMENT '1忘记 2有点印象 3十拿九稳 4完全背诵',
  state_before   TINYINT  DEFAULT NULL,
  state_after    TINYINT  DEFAULT NULL,
  stability      DOUBLE   DEFAULT NULL,
  difficulty     DOUBLE   DEFAULT NULL,
  due_after      DATETIME DEFAULT NULL,
  elapsed_days   INT      DEFAULT NULL,
  scheduled_days INT      DEFAULT NULL,
  reviewed_at    DATETIME NOT NULL,
  created_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, reviewed_at),
  KEY idx_card (card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复习日志（只追加）';
```

**设计理由**：
- `uk_user_question`：一题一卡；也是并发懒建卡的兜底（撞唯一键=别人已建）。
- `idx_user_due (user_id, due)`：会话选题按到期排序。
- 日志只追加：保留"当时的判断"，后续可做参数优化与统计。
- **不再需要"建题即建卡"**：题库与复习解耦；未复习过的题在 t_card 里不存在是正常状态。
- Redis key 规范：
  ```
  wengu:checkin:{userId}:{yyyyMM}   位图 bit(day-1)
  wengu:rank:review:{yyyyMMdd}      ZSet member=userId score=当日复习次数
  ```

**实体**（结构动作：新建包 `review` + 子包 entity/mapper/service/controller/vo）：

```java
// review/entity/ReviewCard.java
package com.wengu.backend.review.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_card")
public class ReviewCard {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long questionId;
    private Integer state;
    private Integer step;
    private Double stability;
    private Double difficulty;
    private LocalDateTime due;
    private LocalDateTime lastReview;
    private Integer reps;
    private Integer lapses;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}
```

```java
// review/entity/ReviewLogEntity.java
package com.wengu.backend.review.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_review_log")
public class ReviewLogEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long cardId;
    private Long questionId;
    private Integer rating;
    private Integer stateBefore;
    private Integer stateAfter;
    private Double stability;
    private Double difficulty;
    private LocalDateTime dueAfter;
    private Integer elapsedDays;
    private Integer scheduledDays;
    private LocalDateTime reviewedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
```

## 2️⃣ 数据访问层（结束时：数据层可自证）

**① 我们现在要干嘛**：两张表 CRUD + "按分类取题"（会话的输入）+ "随机一题"（随机问答入口）。

**② 设计思路**：
- 卡/日志用 `BaseMapper` + LambdaQuery（租户插件自动拼 user_id）
- 按分类取题属于题目域 → 加 `QuestionService.listByCategory`，**不让 review 域碰 QuestionMapper**
- 随机取题用 count + 随机 offset（避免 `ORDER BY RAND()` 全表扫描）

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| ReviewCardMapper.java | review/mapper/ | BaseMapper |
| ReviewLogMapper.java | review/mapper/ | BaseMapper |
| QuestionService.listByCategory / randomOne | **改既有** `question/service/` | 会话输入 + 随机问答 |

```java
// review/mapper/ReviewCardMapper.java
package com.wengu.backend.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.backend.review.entity.ReviewCard;

public interface ReviewCardMapper extends BaseMapper<ReviewCard> {}
```

```java
// review/mapper/ReviewLogMapper.java
package com.wengu.backend.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.backend.review.entity.ReviewLogEntity;

public interface ReviewLogMapper extends BaseMapper<ReviewLogEntity> {}
```

```java
// QuestionService 接口新增
List<Question> listByCategory(Long categoryId);   // categoryId=null → 全部
Question randomOne();

// QuestionServiceImpl 实现
@Override
public List<Question> listByCategory(Long categoryId) {
    return lambdaQuery()
            .eq(categoryId != null, Question::getCategoryId, categoryId)
            .orderByDesc(Question::getUpdatedTime)
            .list();
}

@Override
public Question randomOne() {
    long total = lambdaQuery().count();
    if (total == 0) return null;
    long offset = java.util.concurrent.ThreadLocalRandom.current().nextLong(total);
    return lambdaQuery().last("limit 1 offset " + offset).one();
}
```

> ✅ 本小步可独立验收：单测建 3 道题（两个分类）→ `listByCategory(分类A)` 只返回 A 的题；`randomOne()` 20 次不报错。

## 3️⃣ 业务编排层（结束时：会话→评分→调度→落库全链路可跑）

**① 我们现在要干嘛**：三个业务能力——**复习会话**（懒建卡 + 到期优先 + 随机补足）、**签到**（BitMap）、**榜单**（ZSet）。

**② 设计思路（决策表）**：

| 决策 | 选择 | 理由 |
|---|---|---|
| 建卡时机 | **会话开始时批量懒建** | 题库是仓库，复习是动作；未复习的题不占调度 |
| 选题 | **到期优先 + 随机补足** | 尊重 FSRS 的同时保证每次凑够题（纯随机会让刚背熟的题重复出现） |
| 会话持久化 | **v1 不落库**（前端持有队列） | 刷新丢进度可接受；后续要做"断点续复"再加 session 表（债） |
| fsrs.Card.cardId | `(int)(id & 0x7fffffff)` | 库 API 是 int，仅内部关联；业务主键仍是 Long |
| 时间类型 | FSRS 用 Instant，DB 存 LocalDateTime | 统一经 `ZoneId.systemDefault()` 转换（时区差 8 小时会让 due 错位） |
| 幂等 | 唯一键 + 撞键回查；评分不幂等（前端防连点） | 懒建卡可并发；评分必须一次算一次 |

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| ReviewService.java | review/service/ | 会话选题 / 懒建卡 / 评分调度 / 汇总 / 删题清卡 |
| CheckinService.java | review/service/ | BitMap 签到 + 连续天数 |
| RankService.java | review/service/ | 今日复习榜 |
| QuestionServiceImpl | **改既有** | delete 时清卡（**不再在 create 时建卡**） |

**④ 代码**：

```java
// review/service/ReviewService.java
package com.wengu.backend.review.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.question.entity.Question;
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

        // ② 不足则从未到期卡里随机补足（★ 纯随机会让刚背熟的题重复出现，所以只做补足）
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

    /** 删题时清卡 */
    public void removeByQuestion(Long questionId) {
        cardMapper.delete(Wrappers.<ReviewCard>lambdaQuery()
                .eq(ReviewCard::getQuestionId, questionId));
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
```

```java
// review/service/CheckinService.java
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

        Long monthCount = redis.opsForValue().bitCount(monthKey(uid, YearMonth.from(today)));
        return new CheckinStatusVO(checkedToday, streak, monthCount == null ? 0 : monthCount.intValue());
    }

    private String monthKey(Long uid, YearMonth ym) {
        return "wengu:checkin:" + uid + ":" + ym.format(MONTH);
    }
}
```

```java
// review/service/RankService.java
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
```

```java
// review/vo/（record，只读出参）
// TodayCardVO.java
package com.wengu.backend.review.vo;
public record TodayCardVO(String cardId, String questionId, String title,
                          String answerMd, Integer state, String due) {}

// RatingResultVO.java
package com.wengu.backend.review.vo;
public record RatingResultVO(String cardId, Integer state, String due,
                             Double stability, Double difficulty) {}

// CheckinStatusVO.java
package com.wengu.backend.review.vo;
public record CheckinStatusVO(boolean checkedToday, int streak, int monthCount) {}

// RankItemVO.java
package com.wengu.backend.review.vo;
public record RankItemVO(int rank, String userId, String nickname, int score) {}

// ReviewSummaryVO.java
package com.wengu.backend.review.vo;
public record ReviewSummaryVO(int dueCount) {}
```

**题目域挂接（结构动作：改 `QuestionServiceImpl`）**：
- **不要**在 `create()` 里建卡（v2 变更点）
- `delete()` 末尾追加：`reviewService.removeByQuestion(id);`（注入 ReviewService，跨域 Service 调 Service）

## 4️⃣ 接口编排层（结束时：Apifox 全流程可走）

**① 我们现在要干嘛**：暴露"开始会话 / 评分 / 签到 / 榜单 / 汇总"。
**② 设计思路**：会话开始用 `POST + query`（有副作用：懒建卡）；评分参数 1~4；随机问答独立入口。
**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| ReviewController.java | review/controller/ | session/start、rating、random、summary |
| CheckinController.java | review/controller/ | checkin、status |
| RankController.java | review/controller/ | rank/today |
| application.properties | **追加** | Redis 连接 |

```java
// review/controller/ReviewController.java
package com.wengu.backend.review.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.review.service.ReviewService;
import com.wengu.backend.review.vo.RatingResultVO;
import com.wengu.backend.review.vo.ReviewSummaryVO;
import com.wengu.backend.review.vo.TodayCardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /** 开始复习会话：懒建卡 + 到期优先 + 随机补足 */
    @PostMapping("/session/start")
    public Result<List<TodayCardVO>> start(@RequestParam(required = false) Long categoryId,
                                           @RequestParam(defaultValue = "10") int count) {
        return Result.success(reviewService.startSession(categoryId, count));
    }

    @GetMapping("/summary")
    public Result<ReviewSummaryVO> summary() {
        return Result.success(reviewService.summary());
    }

    @PostMapping("/{cardId}/rating")
    public Result<RatingResultVO> rate(@PathVariable Long cardId, @RequestParam int rating) {
        return Result.success(reviewService.rate(cardId, rating));
    }

    @GetMapping("/random")
    public Result<TodayCardVO> random() {
        return Result.success(reviewService.random());
    }
}
```

```java
// review/controller/CheckinController.java
package com.wengu.backend.review.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.review.service.CheckinService;
import com.wengu.backend.review.vo.CheckinStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/checkin")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;

    @PostMapping
    public Result<CheckinStatusVO> checkin() { return Result.success(checkinService.checkin()); }

    @GetMapping("/status")
    public Result<CheckinStatusVO> status() { return Result.success(checkinService.status()); }
}
```

```java
// review/controller/RankController.java
package com.wengu.backend.review.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.review.service.RankService;
import com.wengu.backend.review.vo.RankItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/rank")
@RequiredArgsConstructor
public class RankController {

    private final RankService rankService;

    @GetMapping("/today")
    public Result<List<RankItemVO>> today(@RequestParam(defaultValue = "10") int top) {
        return Result.success(rankService.todayTop(Math.min(Math.max(top, 1), 50)));
    }
}
```

**配置追加**：

```properties
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=redis123
```

**路由表**：

| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| POST | /review/session/start?categoryId=&count=10 | 分类可空（空=全部） | 懒建卡 + 选题 |
| GET | /review/summary | — | 到期总数（侧栏徽标） |
| POST | /review/{cardId}/rating?rating=1..4 | 1忘记 2有点印象 3十拿九稳 4完全背诵 | 评分调度 |
| GET | /review/random | — | 随机一道（不评分） |
| POST | /checkin | — | 签到 |
| GET | /checkin/status | — | 今日/连续/本月次数 |
| GET | /rank/today?top=10 | — | 今日复习榜 |

> 历史数据**无需回填**：卡在第一次开始复习时自动补齐（这是 v2 懒建卡的自然结果）。

## 5️⃣ 前端联调层（结束时：选分类→开始→卡片流）

**① 我们现在要干嘛**：复习页分两阶段——**准备阶段**（选分类/数量）与**复习阶段**（卡片流+评分）；外加签到与今日榜。
**② 设计思路**：
- 准备阶段：分类 chips（含"全部"）+ 数量选择（5/10/20）+ 「开始复习」
- 复习阶段：本地队列推进，一次拿全；翻面纯前端
- 评分文案：**忘记 / 有点印象 / 十拿九稳 / 完全背诵**（颜色从红到绿）
- 完成后：再来一轮 / 换分类 / 去题库

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| api/review.ts | frontend/src/api/ | 7 个接口 |
| views/ReviewView.vue | frontend/src/views/ | 复习页（两阶段） |
| router/index.ts | **改既有** | 加 review 路由 |
| HomeView.vue | **改既有** | 侧栏「开始复习」+ 到期徽标 |

```ts
// api/review.ts
import request from './request'

export const startSession = (categoryId?: string, count = 10) =>
  request.post('/review/session/start', null, { params: { categoryId, count } })
export const getSummary = () => request.get('/review/summary')
export const rateCard = (cardId: string, rating: number) =>
  request.post(`/review/${cardId}/rating?rating=${rating}`)
export const getRandom = () => request.get('/review/random')
export const checkin = () => request.post('/checkin')
export const checkinStatus = () => request.get('/checkin/status')
export const todayRank = (top = 10) => request.get('/rank/today', { params: { top } })
```

```vue
<!-- views/ReviewView.vue -->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MdPreview } from 'md-editor-v3'
import 'md-editor-v3/lib/preview.css'
import { startSession, rateCard, getRandom, checkin, checkinStatus, todayRank } from '../api/review'
import { listCategory } from '../api/category'

const router = useRouter()

const phase = ref<'setup' | 'review'>('setup')
const loading = ref(false)
const categories = ref<any[]>([])
const categoryId = ref<string | undefined>(undefined)
const count = ref(10)

const queue = ref<any[]>([])
const idx = ref(0)
const showAnswer = ref(false)
const rating = ref(false)

const status = ref<{ checkedToday: boolean; streak: number; monthCount: number }>({ checkedToday: false, streak: 0, monthCount: 0 })
const rank = ref<any[]>([])

const current = computed(() => queue.value[idx.value] ?? null)
const finished = computed(() => phase.value === 'review' && !current.value)

const ratingOptions = [
  { value: 1, label: '忘记', cls: 'again' },
  { value: 2, label: '有点印象', cls: 'hard' },
  { value: 3, label: '十拿九稳', cls: 'good' },
  { value: 4, label: '完全背诵', cls: 'easy' },
]

async function loadMeta() {
  const [cs, st, rk]: any[] = await Promise.all([listCategory(), checkinStatus(), todayRank(10)])
  categories.value = cs ?? []
  status.value = st
  rank.value = rk ?? []
}

onMounted(loadMeta)

async function start() {
  loading.value = true
  try {
    const cards: any = await startSession(categoryId.value, count.value)
    if (!cards?.length) { ElMessage.info('这个分类下还没有题目'); return }
    queue.value = cards
    idx.value = 0
    showAnswer.value = false
    phase.value = 'review'
  } finally { loading.value = false }
}

async function submitRating(value: number) {
  if (!current.value?.cardId || rating.value) return
  rating.value = true
  try {
    await rateCard(current.value.cardId, value)
    idx.value++
    showAnswer.value = false
    const [st, rk]: any[] = await Promise.all([checkinStatus(), todayRank(10)])
    status.value = st
    rank.value = rk ?? []
  } finally { rating.value = false }
}

async function doCheckin() {
  status.value = (await checkin()) as any
  ElMessage.success('签到成功')
}

async function randomOne() {
  const c: any = await getRandom()
  if (!c) { ElMessage.info('题库还是空的'); return }
  queue.value = [c]
  idx.value = 0
  showAnswer.value = false
  phase.value = 'review'
}

function backToSetup() {
  phase.value = 'setup'
  queue.value = []
  idx.value = 0
}
</script>

<template>
  <div class="page-head">
    <div>
      <h1>复习</h1>
      <p class="sub">
        <span :class="{ on: status.checkedToday }">
          {{ status.checkedToday ? `已签到 · 连续 ${status.streak} 天` : '今天还没签到' }}
        </span>
      </p>
    </div>
    <div class="head-ops">
      <el-button v-if="!status.checkedToday" class="primary-btn" @click="doCheckin">签 到</el-button>
      <el-button @click="randomOne">随机问答</el-button>
    </div>
  </div>

  <div class="layout">
    <section class="review-main">
      <!-- 阶段一：准备 -->
      <el-card v-if="phase === 'setup'" class="card setup" shadow="never">
        <h2 class="setup-title">今天想复习哪一类？</h2>
        <p class="hint">开始后会把该分类下的题目纳入复习体系，并抽取 {{ count }} 道组成卡片。</p>

        <div class="cats">
          <button type="button" class="cat-chip" :class="{ on: !categoryId }" @click="categoryId = undefined">全部</button>
          <button v-for="c in categories" :key="c.id" type="button" class="cat-chip"
            :class="{ on: categoryId === String(c.id) }" @click="categoryId = String(c.id)">
            {{ c.name }}<span class="n">{{ c.questionCount }}</span>
          </button>
        </div>

        <div class="count-row">
          <span class="label">数量</span>
          <el-radio-group v-model="count" size="small">
            <el-radio-button :value="5">5</el-radio-button>
            <el-radio-button :value="10">10</el-radio-button>
            <el-radio-button :value="20">20</el-radio-button>
          </el-radio-group>
          <div class="grow" />
          <el-button class="primary-btn" size="large" :loading="loading" @click="start">开始复习</el-button>
        </div>
      </el-card>

      <!-- 阶段二：复习 -->
      <el-card v-else-if="current" class="card" shadow="never">
        <div class="kicker">问 答 卡 片 · {{ idx + 1 }} / {{ queue.length }}</div>
        <h2 class="title">{{ current.title }}</h2>

        <div v-if="!showAnswer" class="self-check">
          <p class="hint">先在脑子里过一遍答案，再翻面核对。</p>
          <el-button class="primary-btn" size="large" @click="showAnswer = true">显示答案</el-button>
        </div>

        <template v-else>
          <div class="answer">
            <MdPreview :modelValue="current.answerMd" previewTheme="github" code-theme="github" />
          </div>
          <div class="rate">
            <span class="rate-label">这次回忆得怎么样？</span>
            <div class="rate-btns">
              <button v-for="o in ratingOptions" :key="o.value" type="button"
                class="rate-btn" :class="o.cls" :disabled="rating"
                @click="submitRating(o.value)">{{ o.label }}</button>
            </div>
          </div>
        </template>
      </el-card>

      <!-- 阶段二：完成 -->
      <el-empty v-else description="这一轮复习完成">
        <div class="empty-btns">
          <el-button class="primary-btn" @click="start">再来一轮</el-button>
          <el-button @click="backToSetup">换个分类</el-button>
          <el-button @click="router.push('/question/list')">去题库</el-button>
        </div>
      </el-empty>

      <div v-if="phase === 'review' && queue.length" class="progress-line">
        <el-progress :percentage="Math.round((idx / Math.max(queue.length, 1)) * 100)"
          :stroke-width="8" :show-text="false" />
        <span class="muted">{{ idx }} / {{ queue.length }}</span>
      </div>
    </section>

    <aside class="rank">
      <h3>今日复习榜</h3>
      <ol v-if="rank.length">
        <li v-for="r in rank" :key="r.userId">
          <span class="no">{{ r.rank }}</span>
          <span class="name">{{ r.nickname }}</span>
          <span class="score">{{ r.score }}</span>
        </li>
      </ol>
      <p v-else class="muted small">今天还没有人复习，你可以是第一个。</p>
    </aside>
  </div>
</template>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; margin-bottom: var(--sp-4) }
.page-head h1 { margin: 0; font-size: var(--fs-24); font-weight: 700; letter-spacing: .3px }
.page-head .sub { margin: 6px 0 0; font-size: var(--fs-12); color: var(--ink-3) }
.page-head .sub .on { color: var(--brand-deep) }
.head-ops { display: flex; gap: var(--sp-2) }
.primary-btn { background: var(--brand-grad); border: none; color: #fff; font-weight: 600; box-shadow: var(--shadow-brand) }

.layout { display: flex; gap: var(--sp-4); align-items: flex-start }
.review-main { flex: 1; min-width: 0 }
.card { border-radius: var(--r-lg); background: var(--card); padding: var(--sp-6) var(--sp-8) }
.setup-title { margin: 0 0 6px; font-size: var(--fs-20); font-weight: 700 }
.hint { color: var(--ink-3); font-size: var(--fs-13); margin: 0 }
.cats { display: flex; gap: 8px; flex-wrap: wrap; margin: var(--sp-4) 0 }
.cat-chip {
  border: 1px solid var(--line); background: var(--card); color: var(--ink-2);
  border-radius: var(--r-pill); padding: 7px 16px; font-size: var(--fs-13); cursor: pointer;
  font-family: inherit; transition: all .15s;
}
.cat-chip:hover { border-color: var(--brand); color: var(--brand-deep) }
.cat-chip.on { background: var(--brand-soft); border-color: var(--brand); color: var(--brand-deep); font-weight: 600 }
.cat-chip .n { margin-left: 6px; font-size: var(--fs-11); color: var(--ink-3) }
.count-row { display: flex; align-items: center; gap: 12px; border-top: 1px solid var(--line); padding-top: var(--sp-4) }
.count-row .label { font-size: var(--fs-12); color: var(--ink-3) }
.count-row .grow { flex: 1 }

.kicker { font-size: var(--fs-11); letter-spacing: 3px; color: var(--brand-deep) }
.title { margin: 10px 0 18px; font-size: var(--fs-20); font-weight: 700; line-height: 1.5 }
.self-check { padding: 26px 0 10px; display: flex; flex-direction: column; align-items: center; gap: 16px }
.answer { border-top: 1px solid var(--line); padding-top: var(--sp-4) }
.answer :deep(.md-editor-preview) { font-size: 14.5px; line-height: 2; background: transparent }
.answer :deep(.md-editor-preview pre) { background: var(--code-bg); border: 1px solid var(--line); border-radius: var(--r-sm) }
.rate { margin-top: var(--sp-5); border-top: 1px solid var(--line); padding-top: var(--sp-4) }
.rate-label { font-size: var(--fs-12); color: var(--ink-3) }
.rate-btns { display: flex; gap: var(--sp-2); margin-top: var(--sp-2); flex-wrap: wrap }
.rate-btn {
  border: 1px solid var(--line); background: var(--card); border-radius: var(--r-sm);
  padding: 9px 20px; font-size: var(--fs-13); cursor: pointer; font-family: inherit; transition: all .15s ease;
}
.rate-btn:hover { transform: translateY(-1px); box-shadow: var(--shadow-sm) }
.rate-btn.again { color: #c0392b; border-color: #f0d3ce }
.rate-btn.hard { color: #b9770e; border-color: #f2e2c8 }
.rate-btn.good { color: var(--brand-deep); border-color: #cfe3f5 }
.rate-btn.easy { color: #2e7d5b; border-color: #cfe8dc }
.progress-line { display: flex; align-items: center; gap: 12px; margin-top: var(--sp-4) }
.progress-line :deep(.el-progress) { flex: 1 }
.rank { width: 260px; flex-shrink: 0; background: var(--card); border: 1px solid var(--line); border-radius: var(--r-lg); padding: var(--sp-4); box-shadow: var(--shadow-sm) }
.rank h3 { margin: 0 0 10px; font-size: var(--fs-13); font-weight: 700 }
.rank ol { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px }
.rank li { display: flex; align-items: center; gap: 8px; font-size: var(--fs-12) }
.rank .no { width: 18px; color: var(--ink-3); font-variant-numeric: tabular-nums }
.rank .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.rank .score { color: var(--brand-deep); font-weight: 600 }
.small { font-size: var(--fs-12) }
.empty-btns { display: flex; gap: 10px; justify-content: center; flex-wrap: wrap }

@media (max-width: 900px) {
  .layout { flex-direction: column }
  .rank { width: 100% }
  .card { padding: var(--sp-4) }
  .count-row { flex-wrap: wrap }
}
</style>
```

**路由追加**：`{ path: 'review', component: () => import('../views/ReviewView.vue') }`

**侧栏入口（HomeView）**：底部两个按钮——「开始复习」（带到期徽标）+「新建题目」（次要样式）；`dueCount` 来自 `/review/summary`，路由变化时刷新。

```ts
import { getSummary } from '../api/review'
const dueCount = ref(0)
function refreshDue() { getSummary().then((s: any) => { dueCount.value = s.dueCount ?? 0 }).catch(() => {}) }
// 现有 watch(route.fullPath) 里追加 refreshDue()；onMounted 末尾也调用一次
```

```vue
<div class="side-actions">
  <button class="review-btn" type="button" @click="router.push('/review')">
    开始复习<span v-if="dueCount > 0" class="badge">{{ dueCount }}</span>
  </button>
  <button class="create-btn secondary" type="button" @click="showCreate = true">＋ 新建题目</button>
</div>
```

```css
.side-actions { display: flex; flex-direction: column; gap: 8px; margin-top: var(--sp-3) }
.review-btn {
  position: relative; width: 100%; border: none; cursor: pointer; color: #fff;
  background: var(--brand-grad); font-size: var(--fs-14); font-weight: 700;
  border-radius: var(--r-md); height: 42px; display: flex; align-items: center; justify-content: center;
  box-shadow: 0 10px 22px rgba(59, 130, 196, .30);
}
.review-btn:hover { transform: translateY(-1px) }
.review-btn .badge {
  position: absolute; right: 10px; top: 50%; transform: translateY(-50%);
  background: rgba(255, 255, 255, .22); border-radius: 999px; font-size: var(--fs-11); padding: 1px 8px;
}
.create-btn.secondary { background: var(--card); color: var(--ink); border: 1px solid var(--line); box-shadow: none }
.create-btn.secondary:hover { border-color: var(--brand); color: var(--brand-deep) }
```

## 🧪 测试与验收

| # | 用例 | 期望 |
|---|---|---|
| T1 | 新建一道题 | **t_card 不新增**（v2 变更点：建题不建卡） |
| T2 | 开始复习（分类 A） | A 下所有题都有卡；B 分类的题**没有**卡 |
| T3 | 会话选题 | 到期卡优先（按 due 升序）；不足 count 时随机补足；返回数 = min(count, A 题数) |
| T4 | 空分类开始复习 | 返回空列表（前端提示） |
| T5 | 评分=3（十拿九稳） | due ≈ +10 分钟；t_review_log 新增；reps+1 |
| T6 | 评分=4（完全背诵） | state=2（REVIEW）；due ≈ +19 天 |
| T7 | 评分=1（忘记） | due ≈ +1 分钟；lapses+1 |
| T8 | 非法评分（0/5） | 400「评分必须是 1~4」 |
| T9 | 越权 | B 对 A 的 cardId 评分 → 404 |
| T10 | 签到 | checkedToday=true、streak≥1；重复签到 bit 幂等 |
| T11 | 榜单 | 复习 3 次后自己出现在今日榜且分数=3；昵称正确 |
| T12 | 删题 | 对应卡被删除；再次开始会话不会出现该题 |

**验收清单**：
- [ ] 12 条全绿
- [ ] 连续两次「开始复习」同分类：第一次全是新卡；第二次优先出现"刚才评过且已到期"的卡（评分=忘记/有点印象的卡几分钟后就到期）
- [ ] 浏览器：选分类→开始→翻面→四档评分→进度推进→完成页→再来一轮
- [ ] 签到/榜单刷新正常；侧栏徽标数=到期卡数
- [ ] commit：`feat(phase5): FSRS复习会话+签到榜单`

> **债务登记**：
> 1. 会话不落库（刷新丢进度）→ 后续要"断点续复"需加 t_review_session
> 2. 随机补足会让"未到期题"提前出现 → 这是为了凑够数量的取舍；若你更想严格，可改成"仅到期"（配置项留到第 7 章）
> 3. 随机问答不参与评分（纯浏览）→ 后续可加"顺手评分"
> 4. 榜单不落库（3 天过期）→ 后续做历史榜/周榜再建快照
> 5. 无 FSRS 参数优化（官方建议用 review_log 训练）→ 结项章进阶
> 6. Element 组件全量引入 → 第 9 章按需导入

## 📦 本章交付物

后端：`review/{entity,mapper,service,controller,vo}` 15 文件 + QuestionService 两方法 + delete 清卡 + properties Redis 三项 + 两张新表。
前端：`api/review.ts` + `ReviewView.vue`（两阶段）+ 路由 + 侧栏复习入口/徽标。
依赖：java-fsrs 1.0.0、spring-boot-starter-data-redis（已装）。

## 🔗 关联八股

- FSRS 三参数与目标保留率；FSRS vs SM-2（固定间隔为什么错）
- **评分文案与算法映射**：Hard/Good/Easy 是回忆质量而非题目难度（产品语义影响数据质量）
- BitMap：1 bit/天、`BITCOUNT`、key TTL 设计
- ZSet：跳表 + 哈希；`ZINCRBY`/`ZREVRANGE`；与第 7 章限流滑窗的关系
- 索引 `(user_id, due)`；EXPLAIN 验证会话选题
- 事务：卡 + 日志同事务；`rollbackFor`
- Instant/LocalDateTime 时区陷阱
- 懒创建模式（lazy initialization）：把"建卡"从写路径移到读路径，减少无用数据

## 下章预告

**07 · 缓存专题（Phase 6）**：题目详情/会话选题/榜单上缓存，JMeter 压出穿透、击穿、雪崩，再逐个修复——第一次拿到"压测前后对比"的硬数据。
