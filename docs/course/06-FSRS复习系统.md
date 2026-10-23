# 06 · FSRS 复习系统（Phase 5）

> 状态：⬜ 待开发 ｜ v1（2026-10-24）
> 本章把"题库"变成"**每天会自己安排复习**的系统"。核心不是页面，而是三件事：
> **调度算法（FSRS）** + **状态持久化（卡片/日志）** + **轻量激励（签到 BitMap / 打卡榜 ZSet）**。

---

## 🎯 本章目标与「我需要什么」

**业务需求（讲人话）**：
你刷了 60 道八股，但人的记忆会衰减。你想要的不是"收藏夹"，而是**每天早上打开就知道今天该复习哪 20 道**、答完给自己评个"忘记/困难/良好/简单"、系统自动安排下次出现时间；顺带用连续签到和今日复习榜给你一点坚持的动力。

**需要**：Phase 0~4 全绿；Redis 容器在线（`docker ps` 里有 `wg-redis`）；依赖已装：
- `io.github.open-spaced-repetition:fsrs:1.0.0`（官方 Java 版 FSRS）
- `spring-boot-starter-data-redis`

> 💡 为什么用官方库而不是自己写 SM-2？FSRS 的公式与参数（19 个权重）是论文级产物，自己实现容易"看起来能跑、实际调度失真"。用官方库 + 自己掌握**状态机与数据落库**，是工程上的正确切分。

## 0️⃣ 本章全景：一次复习的完整旅程

```
① 打开复习页
   GET /review/today  ──> 查 t_card: due <= now ORDER BY due LIMIT 20（索引 idx_user_due）
                      ──> 批量取题目（questionService.listByIds）→ 组装 TodayCardVO
② 看题 → 点"显示答案"（纯前端翻转，不发请求）
③ 评分（忘记/困难/良好/简单）
   POST /review/{cardId}/rating?rating=3
      ──> 载入 t_card → 映射成 fsrs.Card
      ──> Scheduler.reviewCard(card, GOOD, now)  ← 算法在这里
      ──> 回写 t_card（state/step/stability/difficulty/due/last_review/reps）
      ──> 追加 t_review_log（评分、前后状态、间隔）
      ──> Redis ZINCRBY 今日复习榜 +1
      ──> 返回下一张（前端本地队列推进）
④ 签到（可选）
   POST /checkin ──> BitMap SETBIT wengu:checkin:{uid}:{yyyyMM} (day-1) 1
                 ──> 连续天数 = 从今天往回逐位 GETBIT 扫描
⑤ 榜单
   GET /rank/today ──> ZREVRANGE wengu:rank:review:{yyyyMMdd} WITHSCORES → 补昵称
```

**分层定位表**：

| 层 | 产出 | 本层结束系统能干什么 |
|---|---|---|
| 1️⃣ 规格 | t_card / t_review_log + Redis key 规范 + FSRS 数值对照 | 知道"一次复习"要记哪些字段 |
| 2️⃣ 数据层 | ReviewCardMapper / ReviewLogMapper + QuestionService.randomOne | 数据层可自证（到期查询走索引） |
| 3️⃣ 业务编排 | ReviewService / CheckinService / RankService + 与 Question 的挂接 | 命令行/单测可跑通"评分→调度→落库" |
| 4️⃣ 接口编排 | /review/* /checkin /rank/today /review/summary | Apifox 全流程可走 |
| 5️⃣ 前端 | 复习页（翻转/评分/进度/签到/榜单）+ 侧栏入口 | 浏览器每天可用 |

## 1️⃣ 规格定义层（结束时：数据形状钉死）

**先看 FSRS 的真实行为**（我用探针实测 `fsrs-1.0.0` 默认参数，写进笔记）：

| 评分 | 结果状态 | 下次到期 | stability | difficulty |
|---|---|---|---|---|
| 新卡（初始） | LEARNING | 立即 | null | null |
| 1 忘记 AGAIN | LEARNING | +1 分钟 | 0.2172 | 7.0114 |
| 2 困难 HARD | LEARNING | +5.5 分钟 | 1.1771 | 6.2431 |
| 3 良好 GOOD | LEARNING | +10 分钟 | 3.2602 | 4.8846 |
| 4 简单 EASY | **REVIEW**（毕业） | **+19 天** | 16.1507 | 2.4824 |

> ★ 抽问：为什么"简单"会直接毕业到 19 天后，而"良好"只有 10 分钟？——FSRS 用 stability（记忆稳定性）预测"多久后回忆成功率降到目标保留率"；EASY 把 stability 拉得最高，所以间隔最长。**调度不是拍脑袋的 1/3/7 天，而是由记忆参数算出来的。**

**DDL（Navicat 执行）**：

```sql
-- 复习卡：一题一卡，状态由 FSRS 维护
CREATE TABLE t_card (
  id            BIGINT       NOT NULL,
  user_id       BIGINT       NOT NULL,
  question_id   BIGINT       NOT NULL,
  state         TINYINT      NOT NULL DEFAULT 1 COMMENT '1学习 2复习 3重学（fsrs State）',
  step          INT          NOT NULL DEFAULT 0 COMMENT '学习步骤下标',
  stability     DOUBLE       DEFAULT NULL COMMENT '记忆稳定性',
  difficulty    DOUBLE       DEFAULT NULL COMMENT '记忆难度',
  due           DATETIME     NOT NULL COMMENT '下次到期（复习队列唯一依据）',
  last_review   DATETIME     DEFAULT NULL,
  reps          INT          NOT NULL DEFAULT 0 COMMENT '累计复习次数',
  lapses        INT          NOT NULL DEFAULT 0 COMMENT '遗忘次数',
  created_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_question (user_id, question_id),
  KEY idx_user_due (user_id, due)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复习卡';

-- 复习日志：只追加，不更新（后续可做 FSRS 参数优化 / 统计）
CREATE TABLE t_review_log (
  id            BIGINT    NOT NULL,
  user_id       BIGINT    NOT NULL,
  card_id       BIGINT    NOT NULL,
  question_id   BIGINT    NOT NULL,
  rating        TINYINT   NOT NULL COMMENT '1忘记 2困难 3良好 4简单',
  state_before  TINYINT   DEFAULT NULL,
  state_after   TINYINT   DEFAULT NULL,
  stability     DOUBLE    DEFAULT NULL,
  difficulty    DOUBLE    DEFAULT NULL,
  due_after     DATETIME  DEFAULT NULL,
  elapsed_days  INT       DEFAULT NULL,
  scheduled_days INT      DEFAULT NULL,
  reviewed_at   DATETIME  NOT NULL,
  created_time  DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, reviewed_at),
  KEY idx_card (card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复习日志（只追加）';
```

**字段理由**：
- `idx_user_due (user_id, due)`：今日队列就是 `WHERE user_id=? AND due<=now ORDER BY due`，这个索引一条命中（EXPLAIN 会验）。
- `uk_user_question`：一题一卡，**同时是并发创建卡的兜底**（撞唯一键 = 已存在）。
- `stability/difficulty` 允许 NULL：新卡还没算过；用 Double 而不是 decimal，因为 FSRS 全程 double 运算。
- 日志表**不更新**：审计与算法优化都需要历史全量；更新会丢"当时的判断"。
- **Redis key 规范**（写死在本章，后续不散落）：
  ```
  wengu:checkin:{userId}:{yyyyMM}   位图，bit(day-1) = 1 表示当天签到
  wengu:rank:review:{yyyyMMdd}      ZSet，member=userId，score=当日复习次数
  ```

**实体（结构动作：新建包 `review`，子包 entity/mapper/service/controller/vo）**：

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

**① 我们现在要干嘛**：两张表都要 CRUD；另外"随机问答"需要一个随机取题能力（属于题目域，放 `QuestionService`）。

**② 设计思路**：
- 卡与日志都只继承 `BaseMapper`，查询条件用 LambdaQuery（类型安全、租户插件自动拼 user_id）
- **随机取题不用 `ORDER BY RAND()`**（全表扫描 + 临时表排序）：改为"count 后随机 offset"，数据量小但习惯要正确（债：offset 大时仍扫行）

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| ReviewCardMapper.java | review/mapper/ | BaseMapper |
| ReviewLogMapper.java | review/mapper/ | BaseMapper |
| QuestionService.randomOne() | **改既有** `question/service/QuestionService.java` | 随机一题（同域，避免跨域用 Mapper） |

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
// QuestionService 接口新增一行
Question randomOne();
```

```java
// QuestionServiceImpl 新增实现（用已有 count + offset，不写 RAND()）
@Override
public Question randomOne() {
    long total = lambdaQuery().count();
    if (total == 0) return null;
    long offset = ThreadLocalRandom.current().nextLong(total);
    return lambdaQuery().last("limit 1 offset " + offset).one();
}
```

> ✅ 本小步可独立验收：`mvn test` 里写一条测试建 3 道题，随机 20 次应能覆盖到不同题（不要求概率均匀，只要求不报错）。

## 3️⃣ 业务编排层（结束时：评分→调度→落库全链路可跑）

**① 我们现在要干嘛**：把 FSRS 算法包起来，形成三个业务能力——复习（队列/评分）、签到、榜单；并把"建题自动生成卡"挂到题目域。

**② 设计思路（关键决策）**：

| 决策 | 选择 | 理由 |
|---|---|---|
| 何时建卡 | **建题时自动建**（`ensureCard`），另给 SQL 回填历史 | 用户不需要理解"卡"的概念；历史数据用一条 SQL 补齐 |
| 时间类型 | FSRS 用 `Instant`（UTC），DB 存 `DATETIME`（本地） | 统一用 `ZoneId.systemDefault()` 互转；**不要混用 LocalDateTime 与 Instant 比较** |
| fsrs.Card 的 `cardId` | 用 `(int)(id & 0x7fffffff)` | 库的 cardId 是 int（仅内部标识），我们以数据库 Long 为准（★ 抽问） |
| 幂等 | 唯一键 `uk_user_question` + 先查后插 | 并发建卡撞唯一键时回查即可（第 3 章 createIfAbsent 同款信仰） |
| 日志 | 每次评分必写 | 只追加；后续做参数优化/统计都靠它 |

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| ReviewService.java | review/service/ | 建卡 / 今日队列 / 评分调度 / 汇总 / 删题清卡 |
| CheckinService.java | review/service/ | BitMap 签到 + 连续天数 |
| RankService.java | review/service/ | ZSet 今日复习榜 |
| QuestionServiceImpl 挂接 | **改既有** | create 后 ensureCard；delete 时清卡 |

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final Scheduler FSRS = Scheduler.builder().build();
    private static final int DEFAULT_LIMIT = 20;

    private final ReviewCardMapper cardMapper;
    private final ReviewLogMapper logMapper;
    private final QuestionService questionService;
    private final StringRedisTemplate redis;

    // ---------------- 建卡 ----------------
    /** 建题后调用；已存在则跳过（唯一键兜底并发） */
    public void ensureCard(Long questionId) {
        Long exists = cardMapper.selectCount(Wrappers.<ReviewCard>lambdaQuery()
                .eq(ReviewCard::getQuestionId, questionId));
        if (exists > 0) return;
        ReviewCard c = new ReviewCard();
        c.setQuestionId(questionId);
        c.setState(State.LEARNING.getValue());
        c.setStep(0);
        c.setDue(LocalDateTime.now());     // 新卡立即到期 → 今天的队列里就能看到
        c.setReps(0);
        c.setLapses(0);
        cardMapper.insert(c);              // userId 由 MetaObjectHandler 自动填充
    }

    /** 删题时清卡（避免队列里出现"题目已删除"） */
    public void removeByQuestion(Long questionId) {
        cardMapper.delete(Wrappers.<ReviewCard>lambdaQuery()
                .eq(ReviewCard::getQuestionId, questionId));
    }

    // ---------------- 今日队列 ----------------
    public List<TodayCardVO> today(int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        List<ReviewCard> cards = cardMapper.selectList(Wrappers.<ReviewCard>lambdaQuery()
                .le(ReviewCard::getDue, LocalDateTime.now())
                .orderByAsc(ReviewCard::getDue)
                .last("limit " + n));
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

    public ReviewSummaryVO summary() {
        Long due = cardMapper.selectCount(Wrappers.<ReviewCard>lambdaQuery()
                .le(ReviewCard::getDue, LocalDateTime.now()));
        return new ReviewSummaryVO(due == null ? 0 : due.intValue());
    }

    // ---------------- 评分调度（本章心脏） ----------------
    @Transactional(rollbackFor = Exception.class)
    public RatingResultVO rate(Long cardId, int rating) {
        ReviewCard c = cardMapper.selectById(cardId);
        if (c == null) throw new BizException(404, "卡片不存在");
        Rating r = toRating(rating);

        // ① 实体 → fsrs.Card（★ 注意时间类型转换与 cardId 的 int 截断）
        Card fsrsCard = Card.builder()
                .cardId((int) (c.getId() & 0x7fffffff))
                .state(toState(c.getState()))
                .step(c.getStep())
                .stability(c.getStability())
                .difficulty(c.getDifficulty())
                .due(toInstant(c.getDue()))
                .lastReview(toInstant(c.getLastReview()))
                .build();

        // ② 算法：返回新卡 + 本次日志
        Instant now = Instant.now();
        CardAndReviewLog res = FSRS.reviewCard(fsrsCard, r, now);
        Card nc = res.card();

        int stateBefore = c.getState() == null ? State.LEARNING.getValue() : c.getState();
        LocalDateTime lastReviewBefore = c.getLastReview();

        // ③ 回写卡片
        c.setState(nc.getState().getValue());
        c.setStep(nc.getStep());
        c.setStability(nc.getStability());
        c.setDifficulty(nc.getDifficulty());
        c.setDue(toLocal(nc.getDue()));
        c.setLastReview(toLocal(nc.getLastReview()));
        c.setReps((c.getReps() == null ? 0 : c.getReps()) + 1);
        if (r == Rating.AGAIN) c.setLapses((c.getLapses() == null ? 0 : c.getLapses()) + 1);
        cardMapper.updateById(c);

        // ④ 追加日志（只追加）
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
        logRow.setScheduledDays((int) Math.max(0, ChronoUnit.DAYS.between(LocalDateTime.now(), toLocal(nc.getDue()))));
        logRow.setReviewedAt(LocalDateTime.now());
        logMapper.insert(logRow);

        // ⑤ 今日复习榜 +1（ZSet；member 用 userId 字符串）
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        redis.opsForZSet().incrementScore("wengu:rank:review:" + day, String.valueOf(c.getUserId()), 1);
        redis.expire("wengu:rank:review:" + day, Duration.ofDays(3));

        return new RatingResultVO(
                String.valueOf(c.getId()),
                nc.getState().getValue(),
                c.getDue() == null ? null : c.getDue().toString(),
                nc.getStability(),
                nc.getDifficulty());
    }

    // ---------------- 随机问答 ----------------
    public TodayCardVO random() {
        Question q = questionService.randomOne();
        if (q == null) return null;
        return new TodayCardVO(null, String.valueOf(q.getId()), q.getTitle(), q.getAnswerMd(), null, null);
    }

    // ---------------- 工具 ----------------
    private Rating toRating(int v) {
        return switch (v) {
            case 1 -> Rating.AGAIN;
            case 2 -> Rating.HARD;
            case 3 -> Rating.GOOD;
            case 4 -> Rating.EASY;
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
        // 位图 key 必须给 TTL，否则永久堆积（跨月后 40 天清理，够算连续天数）
        redis.expire(key, Duration.ofDays(40));
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
            if (i == 0 && !checkedToday) continue;      // 今天没签，从昨天开始算连续
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
// review/vo/ 全部用 record（只读出参，Jackson 直接序列化）
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

**挂接到题目域**（结构动作：打开 `QuestionServiceImpl`，注入 `ReviewService`，在 create/delete 里各加一行）：

```java
// QuestionServiceImpl 字段追加
private final com.wengu.backend.review.service.ReviewService reviewService;

// create(...) 末尾（return 之前）追加：
reviewService.ensureCard(q.getId());

// delete(...) 末尾追加：
reviewService.removeByQuestion(id);
```

> ⚠️ 跨域调用方向是"题目域 → 复习域"（Service 调 Service），符合第 1 章定的规则。**不要让 ReviewService 反过来调 QuestionMapper。**

> ★ 抽问三连：
> 1. 为什么新卡 `due=now`？（答：让"今天新记的题"立即进入复习队列，而不是等明天）
> 2. `fsrs.Card.cardId` 为什么截断成 int？（答：库的 API 用 int 标识卡，仅用于它内部日志关联；我们以数据库 Long 为准，截断不参与业务主键）
> 3. 签到为什么要给 BitMap key 设 TTL？（答：每月一个 key，不设 TTL 会永久堆积；40 天足够跨月计算连续天数）

## 4️⃣ 接口编排层（结束时：Apifox 全流程可走）

**① 我们现在要干嘛**：暴露复习/签到/榜单接口；参数校验与错误码沿用统一 Result。
**② 设计思路**：评分用 `POST + query 参数`（简单、幂等语义明确）；随机问答返回完整题面与答案（前端翻转）；summary 给侧栏徽标。
**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| ReviewController.java | review/controller/ | /review/today、/review/{id}/rating、/review/random、/review/summary |
| CheckinController.java | review/controller/ | /checkin、/checkin/status |
| RankController.java | review/controller/ | /rank/today |
| application.properties | **追加** | Redis 连接配置 |

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

    @GetMapping("/today")
    public Result<List<TodayCardVO>> today(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(reviewService.today(limit));
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
    public Result<CheckinStatusVO> checkin() {
        return Result.success(checkinService.checkin());
    }

    @GetMapping("/status")
    public Result<CheckinStatusVO> status() {
        return Result.success(checkinService.status());
    }
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

**配置追加**（`application.properties`）：

```properties
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=redis123
```

**路由表**：

| 方法 | 路径 | 入参 | 出参 |
|---|---|---|---|
| GET | /review/today?limit=20 | limit | List\<TodayCardVO\> |
| GET | /review/summary | — | {dueCount} |
| POST | /review/{cardId}/rating?rating=1..4 | rating | RatingResultVO |
| GET | /review/random | — | TodayCardVO |
| POST | /checkin | — | {checkedToday, streak, monthCount} |
| GET | /checkin/status | — | 同上 |
| GET | /rank/today?top=10 | top | List\<RankItemVO\> |

**历史数据回填**（你库里已有题目，需要补卡；`INSERT IGNORE` + 唯一键保证可重复执行）：

```sql
INSERT IGNORE INTO t_card
  (id, user_id, question_id, state, step, due, reps, lapses, created_time, updated_time, deleted)
SELECT UUID_SHORT(), q.user_id, q.id, 1, 0, NOW(), 0, 0, NOW(), NOW(), 0
FROM t_question q
WHERE q.deleted = 0;
```

> 为什么回填可以用 `UUID_SHORT()` 而不是雪花？回填是一次性迁移，ID 只需唯一；日常插入仍走雪花（`ASSIGN_ID`）。

## 5️⃣ 前端联调层（结束时：每天能用的复习闭环）

**① 我们现在要干嘛**：一个"看题→自测→翻面→评分→下一张"的复习页，外加签到与今日榜；侧栏加带到期数量的入口。

**② 设计思路**：
- **本地队列推进**：一次取 20 张，评分后本地 index++，不每张都请求列表（省请求、翻页顺滑）
- 题面/答案**一次拿全**，翻转纯前端（不重复请求）
- 空态三种：今日已清空 / 没有题目（引导导入）/ 加载失败
- 侧栏徽标：进入页面时取 `/review/summary` + `/checkin/status`，路由变化时刷新（复用第 4 章的导航级刷新经验）

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| api/review.ts | frontend/src/api/ | 6 个接口翻译 |
| views/ReviewView.vue | frontend/src/views/ | 复习页 |
| router/index.ts | **改既有** | 加 `review` 子路由 |
| HomeView.vue | **改既有** | 侧栏加「开始复习」+ 签到状态 |

```ts
// api/review.ts
import request from './request'

export const getToday = (limit = 20) => request.get('/review/today', { params: { limit } })
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
import { getToday, rateCard, getRandom, checkin, checkinStatus, todayRank } from '../api/review'

const router = useRouter()

const loading = ref(true)
const queue = ref<any[]>([])
const idx = ref(0)
const showAnswer = ref(false)
const rating = ref(false)
const status = ref<{ checkedToday: boolean; streak: number; monthCount: number }>({ checkedToday: false, streak: 0, monthCount: 0 })
const rank = ref<any[]>([])

const current = computed(() => queue.value[idx.value] ?? null)
const done = computed(() => !loading.value && (!current.value))

const ratingOptions = [
  { value: 1, label: '忘记', cls: 'again' },
  { value: 2, label: '困难', cls: 'hard' },
  { value: 3, label: '良好', cls: 'good' },
  { value: 4, label: '简单', cls: 'easy' },
]

async function load() {
  loading.value = true
  try {
    const [cards, st, rk]: any[] = await Promise.all([getToday(20), checkinStatus(), todayRank(10)])
    queue.value = cards ?? []
    idx.value = 0
    showAnswer.value = false
    status.value = st
    rank.value = rk ?? []
  } finally { loading.value = false }
}

onMounted(load)

async function submitRating(value: number) {
  if (!current.value?.cardId || rating.value) return
  rating.value = true
  try {
    await rateCard(current.value.cardId, value)
    // 本地推进；评分后刷新榜单与签到状态
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
}
</script>

<template>
  <div class="page-head">
    <div>
      <h1>今日复习</h1>
      <p class="sub">
        剩余 {{ Math.max(queue.length - idx, 0) }} 张 ·
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
      <el-skeleton v-if="loading" :rows="8" animated />

      <el-card v-else-if="current" class="card" shadow="never">
        <div class="kicker">问 答 卡 片</div>
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

      <el-empty v-else description="今天的复习已经清空了">
        <div class="empty-btns">
          <el-button class="primary-btn" @click="router.push('/question/list')">去题库看看</el-button>
          <el-button @click="randomOne">随机来一道</el-button>
        </div>
      </el-empty>

      <div v-if="queue.length" class="progress-line">
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
.kicker { font-size: var(--fs-11); letter-spacing: 3px; color: var(--brand-deep) }
.title { margin: 10px 0 18px; font-size: var(--fs-20); font-weight: 700; line-height: 1.5 }
.self-check { padding: 26px 0 10px; display: flex; flex-direction: column; align-items: center; gap: 16px }
.hint { color: var(--ink-3); font-size: var(--fs-13); margin: 0 }
.answer { border-top: 1px solid var(--line); padding-top: var(--sp-4) }
.answer :deep(.md-editor-preview) { font-size: 14.5px; line-height: 2; background: transparent }
.answer :deep(.md-editor-preview pre) { background: var(--code-bg); border: 1px solid var(--line); border-radius: var(--r-sm) }
.rate { margin-top: var(--sp-5); border-top: 1px solid var(--line); padding-top: var(--sp-4) }
.rate-label { font-size: var(--fs-12); color: var(--ink-3) }
.rate-btns { display: flex; gap: var(--sp-2); margin-top: var(--sp-2); flex-wrap: wrap }
.rate-btn {
  border: 1px solid var(--line); background: var(--card); border-radius: var(--r-sm);
  padding: 9px 22px; font-size: var(--fs-13); cursor: pointer; font-family: inherit;
  transition: all .15s ease;
}
.rate-btn:hover { transform: translateY(-1px); box-shadow: var(--shadow-sm) }
.rate-btn.again { color: #c0392b; border-color: #f0d3ce }
.rate-btn.hard { color: #b9770e; border-color: #f2e2c8 }
.rate-btn.good { color: var(--brand-deep); border-color: #cfe3f5 }
.rate-btn.easy { color: #2e7d5b; border-color: #cfe8dc }
.progress-line { display: flex; align-items: center; gap: 12px; margin-top: var(--sp-4) }
.progress-line :deep(.el-progress) { flex: 1 }
.rank {
  width: 260px; flex-shrink: 0; background: var(--card); border: 1px solid var(--line);
  border-radius: var(--r-lg); padding: var(--sp-4); box-shadow: var(--shadow-sm);
}
.rank h3 { margin: 0 0 10px; font-size: var(--fs-13); font-weight: 700 }
.rank ol { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px }
.rank li { display: flex; align-items: center; gap: 8px; font-size: var(--fs-12) }
.rank .no { width: 18px; color: var(--ink-3); font-variant-numeric: tabular-nums }
.rank .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.rank .score { color: var(--brand-deep); font-weight: 600 }
.small { font-size: var(--fs-12) }
.empty-btns { display: flex; gap: 10px; justify-content: center }

@media (max-width: 900px) {
  .layout { flex-direction: column }
  .rank { width: 100% }
  .card { padding: var(--sp-4) }
}
</style>
```

**路由追加**（`router/index.ts` children）：

```ts
{ path: 'review', component: () => import('../views/ReviewView.vue') },
```

**侧栏入口**（HomeView，把底部主按钮改成"复习 + 新建"两枚）：

```vue
<div class="side-actions">
  <button class="review-btn" type="button" @click="router.push('/review')">
    开始复习<span v-if="dueCount > 0" class="badge">{{ dueCount }}</span>
  </button>
  <button class="create-btn secondary" type="button" @click="showCreate = true">＋ 新建题目</button>
</div>
```

```ts
// HomeView script 追加：到期数徽标（路由变化时刷新；onMounted 末尾加一行 refreshDue()）
import { getSummary } from '../api/review'
const dueCount = ref(0)
function refreshDue() { getSummary().then((s: any) => { dueCount.value = s.dueCount ?? 0 }).catch(() => {}) }
watch(() => route.fullPath, () => { refreshCategories(); refreshDue() })
// HomeView 现有 onMounted 的末尾追加一行：
refreshDue()
```

```css
/* HomeView style 追加 */
.side-actions { display: flex; flex-direction: column; gap: 8px; margin-top: var(--sp-3) }
.review-btn {
  position: relative; width: 100%; border: none; cursor: pointer; color: #fff;
  background: var(--brand-grad); font-size: var(--fs-14); font-weight: 700;
  border-radius: var(--r-md); height: 42px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 10px 22px rgba(59, 130, 196, .30);
}
.review-btn:hover { transform: translateY(-1px) }
.review-btn .badge {
  position: absolute; right: 10px; top: 50%; transform: translateY(-50%);
  background: rgba(255, 255, 255, .22); border-radius: 999px;
  font-size: var(--fs-11); padding: 1px 8px;
}
.create-btn { background: var(--card); color: var(--ink); border: 1px solid var(--line); box-shadow: none }
.create-btn:hover { border-color: var(--brand); color: var(--brand-deep) }
```

## 🧪 测试与验收

| # | 用例 | 期望 |
|---|---|---|
| T1 | 新建一道题 | t_card 自动新增一张卡（state=1，due≈now，reps=0） |
| T2 | 回填 SQL | 历史题全部有卡，重复执行不报错（INSERT IGNORE） |
| T3 | GET /review/today | 返回 ≤20 张，按 due 升序；EXPLAIN 走 idx_user_due |
| T4 | 评分=3（良好） | 卡片 due 变为约 10 分钟后；t_review_log 新增一行；reps+1 |
| T5 | 评分=4（简单） | state 变为 2（REVIEW），due 变为 ~19 天后（数值见 1️⃣ 表） |
| T6 | 评分=1（忘记） | due 约 1 分钟后；lapses+1 |
| T7 | 非法评分（0/5） | 400「评分必须是 1~4」 |
| T8 | 越权 | B 对 A 的 cardId 评分 → 404 |
| T9 | 签到 | status.checkedToday=true，streak≥1；重复签到不重复计数（bit 幂等） |
| T10 | 榜单 | 复习几次后 /rank/today 出现自己且分数=复习次数；昵称正确 |
| T11 | 随机问答 | 返回一道题；题库为空时返回 data=null |
| T12 | 删题 | 对应卡被删除，今日队列不再出现 |

**验收清单**：
- [ ] 12 条全绿
- [ ] Redis 里能看到 `wengu:checkin:*` 与 `wengu:rank:review:*`（`redis-cli -a redis123 KEYS 'wengu:*'`）
- [ ] 浏览器：复习 5 张（混合四种评分）→ 进度条推进 → 清空后空态正常
- [ ] 浏览器：签到 → 连续天数显示；刷新不丢
- [ ] commit：`feat(phase5): FSRS复习与签到榜单`

> **债务登记**：
> 1. 今日队列上限 20 硬编码 → 第 7 章配额系统统一成用户配置
> 2. 随机问答用 count+offset（大 offset 仍扫行）→ 第 7 章压测时量化，必要时改 id 池
> 3. 榜单不落库（Redis 3 天过期）→ 后续做历史榜/周榜再建快照表
> 4. 连续天数最多回溯 366 天（够用；跨年长连续需要更长窗口）
> 5. 无 FSRS 参数优化（官方推荐用 py-fsrs/fsrs-rs 基于 review_log 训练）→ 结项章作为进阶方向
> 6. `el-progress`/`el-skeleton` 等 Element 组件仍全量引入 → 第 9 章按需导入

## 📦 本章交付物

后端：`review/{entity,mapper,service,controller,vo}` 共 15 文件 + QuestionService 两处挂接 + properties Redis 三项 + 两张新表 + 回填 SQL。
前端：`api/review.ts` + `ReviewView.vue` + 路由 + 侧栏复习入口/徽标。
依赖：java-fsrs 1.0.0、spring-boot-starter-data-redis（已装）。

## 🔗 关联八股

- **FSRS 三参数**：difficulty（题多难）/ stability（记多牢）/ retrievability（当前可回忆概率）；间隔由目标保留率反推
- FSRS vs SM-2 vs Leitner：为什么"固定 1/3/7 天"是错的；参数化调度
- **BitMap**：签到用位图（1 bit/天，一个月 4 字节）；`BITCOUNT` 统计月签到；key 的 TTL 设计
- **ZSet**：底层跳表 + 哈希表，`ZINCRBY`/`ZREVRANGE` O(logN)；与榜单、限流滑窗的关系（第 7 章限流会复用它）
- 索引：`(user_id, due)` 联合索引支撑"今日队列"；EXPLAIN 验证
- 事务：评分同时写卡+日志必须同事务；`rollbackFor`
- 时间类型：`Instant`(UTC) 与 `LocalDateTime`(本地) 的转换陷阱（时区差 8 小时会让"到期"错位）
- 幂等：重复签到靠 SETBIT 天然幂等；重复评分**不幂等**（会多算一次），前端按钮 loading 防连点

## 下章预告

**07 · 缓存专题（Phase 6）**：给题目详情/今日队列/榜单上缓存，用 JMeter 压出穿透、击穿、雪崩，然后逐个修复——你会第一次拿到"压测前后对比"的硬数据。
