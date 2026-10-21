# 05 · docx 导入管线（Phase 4）

> 状态：⬜ 待开发 ｜ **v2（2026-10-21 需求定稿：解析后人工预览确认 + 散记兜底）**
> 本章是**并发战场的第一仗**，同时是**产品化的一课**：拆题不能盲拆——机器拆完，人来把关。
> 完成后：上传 docx → 自动拆成"题目草稿" → 预览页逐条确认/修改 → 一键转正入题库。

---

## 🎯 本章目标与「我需要什么」

**业务需求（讲人话）**：你积累了一堆八股 docx，想一次导进 wengu。但：

1. **拆题必须自动**：整篇变一道题等于没用（搜不到、复习不了、标签挂不上）
2. **拆错代价很大**：八股笔记里题目标题千奇百怪（`① xxx`、`Q：xxx`、还有大量**裸标题**如 `简单说说B+树`）。纯机器拆必然误伤 → **必须有人工预览确认关卡**
3. **绝不能丢内容**：识别不了的段落、图片，进"散记"兜底题，事后你手动整理

**目标链路**：

```
上传(秒回taskId) → 存盘+哈希去重 → 建任务(0排队) → 发MQ
   → 消费者：CAS 0→1 → DocxParser 拆题(强规则/弱规则/散记) → 写草稿表 → status=2 待确认
   → 预览页：逐条勾选/改标题分类/看答案 → 确认 → 逐条转正入库 → status=4 已完成
                                                        ↘ 放弃 → 草稿清空 status=5
```

**需要**：Phase 0~3 全绿；Docker（MySQL/Redis/RocketMQ）在线；两份真实 docx 备好。

**依赖（我已装好并验证解析通过）**：`poi-ooxml:5.3.0`、`rocketmq-client:5.3.1`。
> 为什么不用 `rocketmq-spring-boot-starter`：Boot 4 生态新，starter 自动装配风险大（MP 搬家的亏吃过一次）。raw client 多写 30 行，换来可控 + 面试可讲。

## 0️⃣ 本章全景：一次导入的完整旅程

```
① 拖入 test.docx
② POST /import/docx ─ 校验(.docx/≤20MB) → 存 data/import/yyyyMM/uuid.docx
                    ─ SHA-256 → 查重(0~4 状态直接复用旧taskId) → INSERT t_import_task(0)
                    ─ 发 MQ {taskId:userId} → 立即返回 taskId          ← 毫秒级
③ MQ 消费（无线程HTTP上下文）
                    ─ UserContext.set(userId)  ★ 身份随消息传递
                    ─ CAS 0→1（防重投重复解析）
                    ─ DocxParser 拆题 → 逐条 INSERT t_import_draft
                    ─ 任务 → status=2 待确认（total=草稿数）
④ 前端 SSE 看到 2 → 跳 /import/preview/{taskId}
⑤ 预览页（人工关卡）
                    ─ 列表：强规则/弱规则/散记 标记 + 标题可改 + 分类可改 + 勾选/删除
                    ─ POST /import/task/{id}/confirm → 逐条转正 → status=4 已完成
                    ─ 或 DELETE /import/task/{id} → 放弃 → status=5
```

**分层定位表**：

| 层 | 产出 | 本层结束系统能干什么 |
|---|---|---|
| 1️⃣ 规格 | t_import_task（六态）+ t_import_draft | 数据形状定死：任务与草稿分离 |
| 2️⃣ 数据层 | ImportTaskMapper(CAS) + ImportDraftMapper | 状态机与草稿存取可独立运转 |
| 3️⃣ 业务编排 | DocxParser(强/弱/散记) + ImportServiceImpl(解析/确认/放弃) + MQ | 命令行跑一次能产出草稿 |
| 4️⃣ 接口编排 | 上传/进度SSE/草稿列表/改草稿/确认/放弃 | Apifox 全流程可走 |
| 5️⃣ 前端 | 导入页 + **预览确认页** + 路由/入口 | 浏览器拖文件 → 预览把关 → 题库出现 |

## 1️⃣ 规格定义层（结束时：数据形状钉死）

**状态机（六态，面试可讲）**：

| status | 含义 | 出口 |
|---|---|---|
| 0 | 排队 | →1 解析中 / →5 投递失败 |
| 1 | 解析中 | →2 待确认 / →5 失败 |
| 2 | **待确认** | →3 导入中 / →5 放弃 |
| 3 | 导入中（确认提交） | →4 已完成 / →5 失败 |
| 4 | 已完成 | 终态 |
| 5 | 已终止（失败/放弃） | 终态；errorMsg 写原因 |

> ★ 为什么"待确认"必须是独立状态？因为解析与入库之间有人工关卡——状态机必须能停在"机器干完、人还没批"的位置。

```sql
CREATE TABLE t_import_task (
  id            BIGINT       NOT NULL,
  user_id       BIGINT       NOT NULL,
  file_name     VARCHAR(255) NOT NULL,
  file_hash     CHAR(64)     NOT NULL COMMENT 'SHA-256 幂等键',
  stored_path   VARCHAR(255) NOT NULL COMMENT 'yyyyMM/uuid.docx',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0排队1解析中2待确认3导入中4完成5终止',
  total_count   INT          NOT NULL DEFAULT 0,
  success_count INT          NOT NULL DEFAULT 0,
  fail_count    INT          NOT NULL DEFAULT 0,
  error_msg     VARCHAR(500) DEFAULT NULL,
  created_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_user_hash (user_id, file_hash),
  KEY idx_user_time (user_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入任务';

CREATE TABLE t_import_draft (
  id            BIGINT      NOT NULL,
  task_id       BIGINT      NOT NULL,
  user_id       BIGINT      NOT NULL,
  seq           INT         NOT NULL COMMENT '文档内顺序，预览排序用',
  title         VARCHAR(100) NOT NULL,
  answer_md     MEDIUMTEXT  NOT NULL,
  category_name VARCHAR(50)  DEFAULT NULL COMMENT '预览时可改；确认时不存在则新建',
  source        VARCHAR(50)  DEFAULT NULL,
  q_type        TINYINT      NOT NULL DEFAULT 0,
  rule_matched  VARCHAR(10)  NOT NULL COMMENT 'STRONG/WEAK/SCATTER（强识别/弱识别/散记）',
  selected      TINYINT      NOT NULL DEFAULT 1 COMMENT '预览勾选态',
  created_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_task (task_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入草稿（确认后清空）';
```

**字段理由**：
- `rule_matched`：**信任分层**。预览页让你一眼知道哪些是"机器很确定"（STRONG）、哪些要重点复核（WEAK）、哪个是"垃圾桶"（SCATTER）。没有这列，人工确认就是闭眼点头。
- `selected`：勾选态落库而不是存前端内存——刷新预览页不丢。
- 草稿表独立于题库表：**绝不让半成品碰脏正式数据**；放弃导入 = 删草稿，题库零污染。
- `seq`：文档顺序是人的阅读顺序，预览必须按它排。

**解析规则表（v2 三档）**：

| 规则 | 判定 | 标记 |
|---|---|---|
| 强 | Heading 样式 → 分类；`①`/`1.`/`Q：` 开头 → 新题 | STRONG |
| 弱 | 分类内、当前题已有答案、且该行：≤40字、不含`，/、`、不以`：。，、；`结尾、不以列表符号开头 → 判为新题 | WEAK |
| 散记 | 无归属的段落/图片/表格（分类开头的前言、无法判定的块）→ `散记 · 分类名` | SCATTER |

## 2️⃣ 数据访问层（结束时：状态机与草稿存取独立可用）

```java
// importjob/entity/ImportTask.java（与 v1 相同，新增 storedPath）
package com.wengu.backend.importjob.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_import_task")
public class ImportTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String fileName;
    private String fileHash;
    private String storedPath;
    private Integer status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private String errorMsg;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}
```

```java
// importjob/entity/ImportDraft.java
package com.wengu.backend.importjob.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_import_draft")
public class ImportDraft {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Long userId;
    private Integer seq;
    private String title;
    private String answerMd;
    private String categoryName;
    private String source;
    private Integer qType;
    private String ruleMatched;      // STRONG / WEAK / SCATTER
    private Integer selected;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
```

```java
// importjob/mapper/ImportTaskMapper.java
package com.wengu.backend.importjob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.backend.importjob.entity.ImportTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ImportTaskMapper extends BaseMapper<ImportTask> {

    /** ★ CAS：排队态→解析中；重复消费在此被挡（返回0=别人已推进） */
    @Update("update t_import_task set status = 1, updated_time = now() " +
            "where id = #{id} and status = 0 and deleted = 0")
    int markParsing(@Param("id") Long id);

    /** 待确认→导入中；确认按钮的并发防重（狂点两下只成功一次） */
    @Update("update t_import_task set status = 3, updated_time = now() " +
            "where id = #{id} and status = 2 and deleted = 0")
    int markImporting(@Param("id") Long id);
}
```

```java
// importjob/mapper/ImportDraftMapper.java
package com.wengu.backend.importjob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.backend.importjob.entity.ImportDraft;

public interface ImportDraftMapper extends BaseMapper<ImportDraft> {}
```

> ⚠️ 两张表都有 `user_id` → 租户插件正常改写；**消费者/SSE 线程必须先 `UserContext.set`**（本章头号考点）。

## 3️⃣ 业务编排层（结束时：一次解析能产出可确认的草稿集）

### 3.1 解析产物与解析器（v2 全码）

```java
// importjob/parser/ParsedQuestion.java
package com.wengu.backend.importjob.parser;

import lombok.Data;

@Data
public class ParsedQuestion {
    public static final String STRONG = "STRONG";
    public static final String WEAK = "WEAK";
    public static final String SCATTER = "SCATTER";

    private String title;
    private String category;
    private String source;
    private String rule;
    private StringBuilder answer = new StringBuilder();
}
```

```java
// importjob/parser/DocxParser.java
package com.wengu.backend.importjob.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DocxParser {

    /** 强规则：① / 1. / Q： */
    private static final Pattern Q_START =
            Pattern.compile("^(?:[①-⑳]|[0-9]{1,2}\\s*[.、．)）]|Q\\s*[:：])");
    private static final Pattern TITLE_PREFIX =
            Pattern.compile("^(?:[①-⑳]|[0-9]{1,2}\\s*[.、．)）]|Q\\s*[:：])\\s*");
    private static final Pattern SOURCE = Pattern.compile("【([^】]{1,50})】");
    /** 弱标题禁止的结尾标点（句子特征） */
    private static final String BAD_END = "：:。，,、；;！!？?”\"'）)";
    private static final List<String> BULLETS = List.of("-", "·", "•", "*", "+", ">");

    @Value("${wengu.media-dir}")
    private String mediaDir;

    /** 解析结果：按文档顺序排列（散记也占位） */
    public List<ParsedQuestion> parse(InputStream in) throws IOException {
        List<ParsedQuestion> out = new ArrayList<>();
        Ctx ctx = new Ctx();                    // 解析上下文（分类/当前题/散记buffer）

        try (XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    handleParagraph(p, ctx, out);
                } else if (el instanceof XWPFTable t) {
                    handleTable(t, ctx);
                }
            }
            finish(ctx, out);
        }
        return out;
    }

    // ---------- 段落 ----------
    private void handleParagraph(XWPFParagraph p, Ctx ctx, List<ParsedQuestion> out) {
        String text = p.getText() == null ? "" : p.getText().trim();
        List<String> images = extractImages(p);

        if (!text.isEmpty() && isHeading(p, text)) {          // 新分类
            flushQuestion(ctx, out);
            flushScatter(ctx, out);
            ctx.category = text.replaceAll("^#+\\s*", "").trim();
            return;
        }
        if (!text.isEmpty() && Q_START.matcher(text).find()) { // 强规则新题
            flushQuestion(ctx, out);
            flushScatter(ctx, out);
            ctx.current = newQuestion(text, ctx.category, ParsedQuestion.STRONG);
            appendImages(ctx, images);
            return;
        }
        if (!text.isEmpty() && isWeakTitle(text) && ctx.current != null
                && ctx.current.getAnswer().length() > 0) {     // 弱规则新题
            flushQuestion(ctx, out);
            ctx.current = newQuestion(text, ctx.category, ParsedQuestion.WEAK);
            appendImages(ctx, images);
            return;
        }
        // 普通正文
        if (!text.isEmpty()) {
            if (ctx.current != null) ctx.current.getAnswer().append(text).append("\n\n");
            else ctx.scatter.append(text).append("\n\n");       // 无归属 → 散记
        }
        appendImages(ctx, images);
    }

    private void handleTable(XWPFTable t, Ctx ctx) {
        String md = isSingleCell(t) ? toCodeBlock(t) : toMarkdownTable(t);
        if (ctx.current != null) ctx.current.getAnswer().append(md).append("\n");
        else ctx.scatter.append(md).append("\n");
    }

    // ---------- 判定 ----------
    private boolean isHeading(XWPFParagraph p, String text) {
        String style = p.getStyle();
        if (style != null) {
            String s = style.toLowerCase();
            if (s.contains("heading") || style.contains("标题")) return true;
        }
        return text.startsWith("#");
    }

    /**
     * ★ 弱规则（本版核心）：裸标题启发式
     *  ≤40字、不含逗号顿号、不以句读结尾、不以列表符号开头 → 视为可能的新题
     *  再叠加"当前题已有答案"作为前置条件（在调用处判断）
     */
    private boolean isWeakTitle(String text) {
        if (text.length() == 0 || text.length() > 40) return false;
        if (text.contains("，") || text.contains("、") || text.contains(",")) return false;
        if (BAD_END.indexOf(text.charAt(text.length() - 1)) >= 0) return false;
        for (String b : BULLETS) if (text.startsWith(b)) return false;
        return true;
    }

    // ---------- 组装 ----------
    private ParsedQuestion newQuestion(String text, String category, String rule) {
        ParsedQuestion q = new ParsedQuestion();
        q.setCategory(category);
        q.setRule(rule);
        Matcher m = SOURCE.matcher(text);
        if (m.find()) q.setSource(m.group(1));
        String title = TITLE_PREFIX.matcher(text).replaceFirst("").trim();
        q.setTitle(truncate(title.isEmpty() ? text : title, 100));
        return q;
    }

    private void appendImages(Ctx ctx, List<String> images) {
        if (images.isEmpty()) return;
        StringBuilder target = ctx.current != null ? ctx.current.getAnswer() : ctx.scatter;
        for (String url : images) target.append("![](").append(url).append(")\n\n");
    }

    private void flushQuestion(Ctx ctx, List<ParsedQuestion> out) {
        if (ctx.current != null) {
            if (ctx.current.getAnswer().length() == 0) ctx.current.getAnswer().append("（空）\n");
            out.add(ctx.current);
            ctx.current = null;
        }
    }

    /** ★ 散记兜底：任何无归属内容都不许丢 */
    private void flushScatter(Ctx ctx, List<ParsedQuestion> out) {
        if (ctx.scatter.length() == 0) return;
        ParsedQuestion q = new ParsedQuestion();
        q.setRule(ParsedQuestion.SCATTER);
        q.setCategory(ctx.category);
        q.setTitle(truncate("散记 · " + (ctx.category == null ? "未分类" : ctx.category), 100));
        q.setAnswer(new StringBuilder(ctx.scatter.toString()));
        out.add(q);
        ctx.scatter.setLength(0);
    }

    private void finish(Ctx ctx, List<ParsedQuestion> out) {
        flushQuestion(ctx, out);
        flushScatter(ctx, out);
    }

    // ---------- 表格/图片工具 ----------
    private boolean isSingleCell(XWPFTable t) {
        return t.getRows().size() == 1 && t.getRow(0).getTableCells().size() == 1;
    }

    private String toCodeBlock(XWPFTable t) {
        return "```java\n" + t.getRow(0).getCell(0).getText().trim() + "\n```\n";
    }

    private String toMarkdownTable(XWPFTable t) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = t.getRows();
        for (int i = 0; i < rows.size(); i++) {
            List<XWPFTableCell> cells = rows.get(i).getTableCells();
            sb.append("|");
            for (XWPFTableCell c : cells) {
                sb.append(" ").append(c.getText().replace("\n", " ").trim()).append(" |");
            }
            sb.append("\n");
            if (i == 0) {
                sb.append("|");
                for (int j = 0; j < cells.size(); j++) sb.append(" --- |");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private List<String> extractImages(XWPFParagraph p) {
        List<String> urls = new ArrayList<>();
        for (XWPFRun run : p.getRuns()) {
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                try {
                    XWPFPictureData data = pic.getPictureData();
                    String ext = data.suggestFileExtension();
                    String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
                    Path dir = Paths.get(mediaDir, "import", month);
                    Files.createDirectories(dir);
                    String name = UUID.randomUUID() + "." + ext;
                    Files.write(dir.resolve(name), data.getData());
                    urls.add("/media/import/" + month + "/" + name);
                } catch (IOException e) {
                    log.warn("图片提取失败，跳过", e);
                }
            }
        }
        return urls;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 解析上下文 */
    private static class Ctx {
        String category;
        ParsedQuestion current;
        StringBuilder scatter = new StringBuilder();
    }
}
```

> ★ 抽问四连：
> 1. 为什么散记内容要"在遇到新题前 flush"，而不是最后统一收？（答：保文档顺序，seq 才有意义）
> 2. 弱规则为什么要求"当前题已有答案"？（答：否则把一道题的连续短段落全部拆碎）
> 3. 为什么弱标题禁逗号/顿号？（答：正文列举句高频含逗号，标题极少——这是最便宜的误判过滤器）
> 4. 单格表格为什么判定为代码块？（答：你手撕文档的存储习惯）

### 3.2 MQ 接线（raw client）

```java
// config/RocketMQConfig.java
package com.wengu.backend.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RocketMQConfig {
    public static final String TOPIC_IMPORT = "wengu-import-task";

    @Value("${wengu.rocketmq.namesrv}")
    private String namesrv;

    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer importProducer() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("wengu-import-producer");
        producer.setNamesrvAddr(namesrv);
        producer.start();
        return producer;
    }
}
```

```java
// importjob/consumer/ImportTaskConsumer.java
package com.wengu.backend.importjob.consumer;

import com.wengu.backend.config.RocketMQConfig;
import com.wengu.backend.importjob.service.ImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTaskConsumer implements InitializingBean, DisposableBean {

    private final ImportService importService;

    @Value("${wengu.rocketmq.namesrv}")
    private String namesrv;

    private DefaultMQPushConsumer consumer;

    @Override
    public void afterPropertiesSet() throws Exception {
        consumer = new DefaultMQPushConsumer("wengu-import-consumer");
        consumer.setNamesrvAddr(namesrv);
        consumer.subscribe(RocketMQConfig.TOPIC_IMPORT, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt msg : msgs) {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                String[] parts = body.split(":");
                if (parts.length != 2) { log.error("非法消息体 {}", body); continue; }
                Long taskId = Long.valueOf(parts[0]);
                Long userId = Long.valueOf(parts[1]);
                try {
                    importService.parseToDrafts(taskId, userId);
                } catch (Exception e) {
                    // ★ 不 RECONSUME_LATER：重投时 CAS 因 status=1 失败会跳过 → 任务卡死
                    log.error("解析异常（已置失败）taskId={}", taskId, e);
                    importService.markFailed(taskId, userId, "解析异常：" + e.getClass().getSimpleName());
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("导入消费者启动 topic={}", RocketMQConfig.TOPIC_IMPORT);
    }

    @Override
    public void destroy() {
        if (consumer != null) consumer.shutdown();
    }
}
```

### 3.3 ImportService（解析→草稿→确认，全码）

```java
// importjob/service/ImportService.java
package com.wengu.backend.importjob.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.wengu.backend.importjob.entity.ImportDraft;
import com.wengu.backend.importjob.entity.ImportTask;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface ImportService extends IService<ImportTask> {

    Long upload(MultipartFile file, Long userId);

    /** 消费者入口：解析→写草稿→status=2（userId 来自消息，无线程上下文） */
    void parseToDrafts(Long taskId, Long userId);

    void markFailed(Long taskId, Long userId, String reason);

    /** 预览：草稿列表（按 seq） */
    List<ImportDraft> listDrafts(Long taskId);

    /** 预览：修改单条草稿（标题/分类/来源/勾选/题型） */
    void updateDraft(Long draftId, String title, String categoryName, String source,
                     Integer selected, Integer qType);

    /** 确认：勾选草稿转正入库 → status=4；返回 {total, success, fail} */
    Map<String, Integer> confirm(Long taskId, Long userId);

    /** 放弃：删草稿 → status=5 */
    void discard(Long taskId, Long userId);
}
```

```java
// importjob/service/ImportServiceImpl.java
package com.wengu.backend.importjob.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.common.util.UserContext;
import com.wengu.backend.config.RocketMQConfig;
import com.wengu.backend.importjob.entity.ImportDraft;
import com.wengu.backend.importjob.entity.ImportTask;
import com.wengu.backend.importjob.mapper.ImportDraftMapper;
import com.wengu.backend.importjob.mapper.ImportTaskMapper;
import com.wengu.backend.importjob.parser.DocxParser;
import com.wengu.backend.importjob.parser.ParsedQuestion;
import com.wengu.backend.question.dto.QuestionCreateDTO;
import com.wengu.backend.question.service.CategoryService;
import com.wengu.backend.question.service.QuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportServiceImpl extends ServiceImpl<ImportTaskMapper, ImportTask> implements ImportService {

    private final DocxParser docxParser;
    private final ImportDraftMapper draftMapper;
    private final QuestionService questionService;
    private final CategoryService categoryService;
    private final DefaultMQProducer importProducer;

    @Value("${wengu.import-dir}")
    private String importDir;

    // ---------------- 上传 ----------------
    @Override
    public Long upload(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) throw new BizException(400, "空文件");
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".docx"))
            throw new BizException(400, "仅支持 .docx（旧版 .doc 请另存为 docx）");
        if (file.getSize() > 20L * 1024 * 1024) throw new BizException(400, "文件最大20MB");

        byte[] bytes;
        try { bytes = file.getBytes(); } catch (Exception e) { throw new BizException(500, "读取文件失败"); }
        String hash = sha256(bytes);

        // 幂等：同用户同内容（未终止的任务）直接复用
        ImportTask exist = lambdaQuery()
                .eq(ImportTask::getFileHash, hash)
                .in(ImportTask::getStatus, 0, 1, 2, 3, 4)
                .last("limit 1").one();
        if (exist != null) return exist.getId();

        String stored = storeFile(bytes);
        ImportTask task = new ImportTask();
        task.setUserId(userId);
        task.setFileName(name.length() > 200 ? name.substring(0, 200) : name);
        task.setFileHash(hash);
        task.setStoredPath(stored);
        task.setStatus(0);
        task.setTotalCount(0); task.setSuccessCount(0); task.setFailCount(0);
        save(task);
        UserContext.clear();

        try {
            importProducer.send(new Message(RocketMQConfig.TOPIC_IMPORT,
                    (task.getId() + ":" + userId).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("MQ 发送失败 taskId={}", task.getId(), e);
            markFailed(task.getId(), userId, "任务投递失败，请检查 RocketMQ 后重试");
        }
        return task.getId();
    }

    // ---------------- 解析（MQ 消费） ----------------
    @Override
    public void parseToDrafts(Long taskId, Long userId) {
        UserContext.set(userId);                       // ★ 先身份，后操作
        try {
            if (getBaseMapper().markParsing(taskId) == 0) {
                log.info("任务非排队态，跳过 taskId={}", taskId);
                return;
            }
            ImportTask task = getById(taskId);
            if (task == null) return;

            List<ParsedQuestion> parsed;
            try (FileInputStream in = new FileInputStream(
                    Paths.get(importDir, task.getStoredPath()).toFile())) {
                parsed = docxParser.parse(in);
            }

            int seq = 0;
            for (ParsedQuestion pq : parsed) {
                ImportDraft d = new ImportDraft();
                d.setTaskId(taskId);
                d.setUserId(userId);
                d.setSeq(seq++);
                d.setTitle(pq.getTitle());
                d.setAnswerMd(pq.getAnswer().toString());
                d.setCategoryName(pq.getCategory());
                d.setSource(pq.getSource());
                d.setQType(0);
                d.setRuleMatched(pq.getRule());
                d.setSelected(1);
                draftMapper.insert(d);
            }

            lambdaUpdate().eq(ImportTask::getId, taskId)
                    .set(ImportTask::getStatus, 2)          // 待确认
                    .set(ImportTask::getTotalCount, parsed.size())
                    .update();
            log.info("解析完成 taskId={} drafts={}", taskId, parsed.size());
        } catch (Exception e) {
            log.error("解析失败 taskId={}", taskId, e);
            markFailed(taskId, userId, truncateRoot(e));
        } finally {
            UserContext.clear();                        // ★ 池化线程必清
        }
    }

    @Override
    public void markFailed(Long taskId, Long userId, String reason) {
        UserContext.set(userId);
        try {
            lambdaUpdate().eq(ImportTask::getId, taskId)
                    .set(ImportTask::getStatus, 5)
                    .set(ImportTask::getErrorMsg, reason)
                    .update();
        } finally {
            UserContext.clear();
        }
    }

    // ---------------- 预览 ----------------
    @Override
    public List<ImportDraft> listDrafts(Long taskId) {
        ImportTask task = getById(taskId);               // 租户隔离：他人任务查无
        if (task == null) throw new BizException(404, "任务不存在");
        return draftMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ImportDraft>lambdaQuery()
                        .eq(ImportDraft::getTaskId, taskId)
                        .orderByAsc(ImportDraft::getSeq));
    }

    @Override
    public void updateDraft(Long draftId, String title, String categoryName,
                            String source, Integer selected, Integer qType) {
        ImportDraft d = draftMapper.selectById(draftId);
        if (d == null) throw new BizException(404, "草稿不存在");
        if (title != null && !title.isBlank()) d.setTitle(title.length() > 100 ? title.substring(0, 100) : title);
        if (categoryName != null) d.setCategoryName(categoryName.isBlank() ? null : categoryName);
        if (source != null) d.setSource(source);
        if (selected != null) d.setSelected(selected == 0 ? 0 : 1);
        if (qType != null && qType >= 0 && qType <= 3) d.setQType(qType);
        draftMapper.updateById(d);
    }

    // ---------------- 确认转正 ----------------
    @Override
    public Map<String, Integer> confirm(Long taskId, Long userId) {
        UserContext.set(userId);
        try {
            if (getBaseMapper().markImporting(taskId) == 0)
                throw new BizException(400, "任务不在待确认状态（可能已处理）");

            List<ImportDraft> drafts = draftMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<ImportDraft>lambdaQuery()
                            .eq(ImportDraft::getTaskId, taskId)
                            .eq(ImportDraft::getSelected, 1)
                            .orderByAsc(ImportDraft::getSeq));

            int ok = 0, fail = 0;
            for (ImportDraft d : drafts) {
                try {
                    QuestionCreateDTO dto = new QuestionCreateDTO();
                    dto.setTitle(d.getTitle());
                    dto.setAnswerMd(d.getAnswerMd());
                    dto.setCategoryId(categoryService.getOrCreate(d.getCategoryName()));
                    dto.setTagNames(List.of());
                    dto.setQType(d.getQType() == null ? 0 : d.getQType());
                    dto.setSource(d.getSource());
                    dto.setDifficulty(0);
                    questionService.create(dto);
                    ok++;
                } catch (Exception e) {
                    fail++;
                    log.warn("草稿转正失败 id={} title={}", d.getId(), d.getTitle(), e);
                }
            }

            // 草稿使命完成：全部清空（未勾选的也不留——任务将关闭）
            draftMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ImportDraft>lambdaQuery().eq(ImportDraft::getTaskId, taskId));

            lambdaUpdate().eq(ImportTask::getId, taskId)
                    .set(ImportTask::getStatus, 4)
                    .set(ImportTask::getSuccessCount, ok)
                    .set(ImportTask::getFailCount, fail)
                    .update();

            Map<String, Integer> r = new HashMap<>();
            r.put("total", drafts.size()); r.put("success", ok); r.put("fail", fail);
            return r;
        } finally {
            UserContext.clear();
        }
    }

    @Override
    public void discard(Long taskId, Long userId) {
        UserContext.set(userId);
        try {
            ImportTask task = getById(taskId);
            if (task == null) throw new BizException(404, "任务不存在");
            if (task.getStatus() != 2) throw new BizException(400, "只有待确认的任务可以放弃");
            draftMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ImportDraft>lambdaQuery().eq(ImportDraft::getTaskId, taskId));
            lambdaUpdate().eq(ImportTask::getId, taskId)
                    .set(ImportTask::getStatus, 5)
                    .set(ImportTask::getErrorMsg, "已放弃导入")
                    .update();
        } finally {
            UserContext.clear();
        }
    }

    // ---------------- 工具 ----------------
    private String storeFile(byte[] bytes) {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        try {
            Path dir = Paths.get(importDir, month);
            Files.createDirectories(dir);
            String fileName = UUID.randomUUID() + ".docx";
            Files.write(dir.resolve(fileName), bytes);
            return month + "/" + fileName;
        } catch (Exception e) {
            throw new BizException(500, "文件保存失败");
        }
    }

    private String truncateRoot(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 450 ? m.substring(0, 450) : m;
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

先补分类工具（结构动作：打开 `question/service/CategoryService.java` 加一行，`CategoryServiceImpl` 加实现）：

```java
// CategoryService 接口新增
Long getOrCreate(String name);

// CategoryServiceImpl 新增实现
@Override
public Long getOrCreate(String name) {
    if (name == null || name.isBlank()) return null;
    Category c = lambdaQuery().eq(Category::getName, name).one();
    if (c != null) return c.getId();
    CategoryDTO dto = new CategoryDTO();
    dto.setName(name.length() > 50 ? name.substring(0, 50) : name);
    return create(dto);
}
```

> ★ 三个大考点（本章笔记必须写）：
> 1. **异步线程无 HTTP 上下文**：userId 随消息走；消费者第一行 `UserContext.set`，`finally clear`。
> 2. **不要盲目 RECONSUME_LATER**：CAS 已把 status 推到 1，重投会被 CAS 挡掉 → 任务永久卡"解析中"。内部异常直接落 5。
> 3. **确认按钮要防并发**：`markImporting` 的 CAS（2→3）保证狂点两下只导入一次。

## 4️⃣ 接口编排层（结束时：Apifox 全流程可走）

```java
// config/SseConfig.java
package com.wengu.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SseConfig {
    @Bean("sseExecutor")
    public ThreadPoolTaskExecutor sseExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("sse-");
        ex.initialize();
        return ex;
    }
}
```

```java
// importjob/controller/ImportController.java
package com.wengu.backend.importjob.controller;

import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.common.result.Result;
import com.wengu.backend.common.util.UserContext;
import com.wengu.backend.importjob.entity.ImportDraft;
import com.wengu.backend.importjob.entity.ImportTask;
import com.wengu.backend.importjob.service.ImportService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @Qualifier("sseExecutor")
    private final ThreadPoolTaskExecutor sseExecutor;

    @Data @AllArgsConstructor
    public static class TaskVO {
        private Long id;
        private Integer status;
        private Integer totalCount;
        private Integer successCount;
        private Integer failCount;
        private String errorMsg;
        private String fileName;
    }

    @Data
    public static class DraftUpdateDTO {
        private String title;
        private String categoryName;
        private String source;
        private Integer selected;
        private Integer qType;
    }

    @PostMapping("/docx")
    public Result<Long> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(importService.upload(file, UserContext.requireUserId()));
    }

    @GetMapping("/task/{id}")
    public Result<TaskVO> task(@PathVariable Long id) {
        ImportTask t = importService.getById(id);
        if (t == null) throw new BizException(404, "任务不存在");
        return Result.success(toVO(t));
    }

    @GetMapping("/task/{id}/drafts")
    public Result<List<ImportDraft>> drafts(@PathVariable Long id) {
        return Result.success(importService.listDrafts(id));
    }

    @PutMapping("/draft/{id}")
    public Result<Void> updateDraft(@PathVariable Long id, @RequestBody DraftUpdateDTO dto) {
        importService.updateDraft(id, dto.getTitle(), dto.getCategoryName(),
                dto.getSource(), dto.getSelected(), dto.getQType());
        return Result.success();
    }

    @PostMapping("/task/{id}/confirm")
    public Result<Map<String, Integer>> confirm(@PathVariable Long id) {
        return Result.success(importService.confirm(id, UserContext.requireUserId()));
    }

    @DeleteMapping("/task/{id}")
    public Result<Void> discard(@PathVariable Long id) {
        importService.discard(id, UserContext.requireUserId());
        return Result.success();
    }

    /** SSE：EventSource 不能带头 → token 走 query（拦截器已兼容） */
    @GetMapping("/task/{id}/progress")
    public SseEmitter progress(@PathVariable Long id) {
        Long userId = UserContext.requireUserId();
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        sseExecutor.execute(() -> {
            UserContext.set(userId);                      // ★ 跨线程补上下文
            try {
                for (int i = 0; i < 600; i++) {
                    ImportTask t = importService.getById(id);
                    if (t == null) { emitter.completeWithError(new BizException(404, "任务不存在")); return; }
                    emitter.send(SseEmitter.event().name("progress").data(toVO(t)));
                    int s = t.getStatus() == null ? 0 : t.getStatus();
                    if (s == 2 || s == 4 || s == 5) { emitter.complete(); return; }  // 待确认/完成/终止都收尾
                    TimeUnit.MILLISECONDS.sleep(500);
                }
                emitter.complete();
            } catch (Exception e) {
                try { emitter.completeWithError(e); } catch (Exception ignored) { }
            } finally {
                UserContext.clear();                      // ★ 池化线程必清
            }
        });
        return emitter;
    }

    private TaskVO toVO(ImportTask t) {
        return new TaskVO(t.getId(), t.getStatus(), t.getTotalCount(),
                t.getSuccessCount(), t.getFailCount(), t.getErrorMsg(), t.getFileName());
    }
}
```

**登录拦截器小改**（结构动作：打开 `security/LoginInterceptor.java`，token 提取改为"头优先，query 兜底"）：

```java
String auth = req.getHeader("Authorization");
String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
if (token == null) token = req.getParameter("token");   // EventSource 只能走 query
Long userId = token == null ? null : JwtUtil.parse(token);
```

**配置追加**（`application.properties`）：

```properties
wengu.rocketmq.namesrv=localhost:9876
wengu.import-dir=D:/JavaCode/wengu/data/import
```

**路由表**：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /import/docx | 上传，秒回 taskId |
| GET | /import/task/{id} | 轮询兜底 |
| GET | /import/task/{id}/progress?token= | SSE 进度（2/4/5 自动收尾） |
| GET | /import/task/{id}/drafts | 预览草稿列表 |
| PUT | /import/draft/{id} | 改标题/分类/来源/勾选/题型 |
| POST | /import/task/{id}/confirm | 确认转正 |
| DELETE | /import/task/{id} | 放弃导入 |

## 5️⃣ 前端联调层（结束时：拖文件 → 预览把关 → 题库见题）

**结构动作**：新建 `api/import.ts`（追加）、`views/ImportView.vue`、**`views/ImportPreviewView.vue`**；`router/index.ts` 加两条子路由（`import`、`import/preview/:taskId`）；HomeView 侧栏加"导入 docx"入口。

```ts
// api/import.ts（全量）
import request from './request'

export const uploadDocx = (file: File) => {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/import/docx', fd)
}
export const getTask = (id: string) => request.get(`/import/task/${id}`)
export const getDrafts = (id: string) => request.get(`/import/task/${id}/drafts`)
export const updateDraft = (id: string, d: any) => request.put(`/import/draft/${id}`, d)
export const confirmImport = (id: string) => request.post(`/import/task/${id}/confirm`)
export const discardImport = (id: string) => request.delete(`/import/task/${id}`)
```

```vue
<!-- views/ImportView.vue -->
<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { uploadDocx } from '../api/import'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()

const file = ref<File | null>(null)
const taskId = ref<string>('')
const status = ref(0)
const total = ref(0)
const errorMsg = ref('')
const uploading = ref(false)
let es: EventSource | null = null

const statusText = ['排队中…', '解析中…', '待确认（正在跳转预览）', '导入中…', '已完成', '已终止']

function onFileChange(f: UploadFile) {
  file.value = (f.raw ?? null) as File | null
}

async function start() {
  if (!file.value) { ElMessage.warning('先选一个 .docx 文件'); return }
  uploading.value = true
  try {
    taskId.value = (await uploadDocx(file.value)) as unknown as string
    status.value = 0
    subscribe(taskId.value)
  } finally { uploading.value = false }
}

function subscribe(id: string) {
  es?.close()
  es = new EventSource(`/api/import/task/${id}/progress?token=${store.token}`)
  es.addEventListener('progress', (ev: MessageEvent) => {
    const t = JSON.parse(ev.data)
    status.value = t.status; total.value = t.totalCount ?? 0; errorMsg.value = t.errorMsg ?? ''
    if (t.status === 2) {                 // 草稿就绪 → 去预览
      es?.close(); es = null
      router.push(`/import/preview/${id}`)
    }
    if (t.status >= 4) { es?.close(); es = null }
  })
  es.onerror = () => { es?.close(); es = null }
}

onBeforeUnmount(() => { es?.close() })
</script>

<template>
  <el-card class="wrap" shadow="never">
    <h3>导入 docx 题库</h3>
    <p class="soft">
      支持 .docx（20MB 内）。解析完成后先进入<b>预览确认</b>：你可以修改标题/分类、取消勾选不需要的条目，
      确认后才会写入题库。识别不了的内容会收进「散记」条目，不会丢失。
    </p>
    <el-upload drag :auto-upload="false" :limit="1" accept=".docx"
      :on-change="onFileChange" :on-exceed="() => ElMessage.warning('一次一个文件')">
      <div class="drop">把 docx 拖进来，或点击选择</div>
    </el-upload>

    <div class="ops">
      <el-button type="primary" :disabled="!file || uploading" :loading="uploading" @click="start">
        开始解析
      </el-button>
    </div>

    <div v-if="taskId" class="prog">
      <div class="line">{{ statusText[status] ?? '未知' }}
        <span v-if="total" class="soft">（已识别 {{ total }} 条）</span>
      </div>
      <el-progress :percentage="status >= 2 ? 100 : 40" :indeterminate="status <= 1"
        :status="status === 5 ? 'exception' : undefined" :stroke-width="10" />
      <el-alert v-if="status === 5" type="error" :closable="false" :title="errorMsg || '解析失败'" />
    </div>
  </el-card>
</template>

<style scoped>
.wrap { border-radius: 14px; border: 1px solid var(--line); max-width: 760px }
.soft { color: var(--ink-soft); font-size: 12px; line-height: 1.9 }
.drop { padding: 26px 0; color: var(--ink-soft) }
.ops { margin: 16px 0 }
.prog { display: flex; flex-direction: column; gap: 10px }
.line { font-weight: 600; color: var(--ink) }
</style>
```

```vue
<!-- views/ImportPreviewView.vue -->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MdPreview } from 'md-editor-v3'
import 'md-editor-v3/lib/preview.css'
import { getDrafts, updateDraft, confirmImport, discardImport } from '../api/import'
import { listCategory } from '../api/category'

const route = useRoute()
const router = useRouter()
const taskId = route.params.taskId as string

const loading = ref(true)
const drafts = ref<any[]>([])
const categories = ref<any[]>([])
const submitting = ref(false)

const ruleName: Record<string, string> = { STRONG: '强识别', WEAK: '弱识别', SCATTER: '散记' }
const ruleType: Record<string, string> = { STRONG: 'success', WEAK: 'warning', SCATTER: 'info' }
const selectedCount = computed(() => drafts.value.filter(d => d.selected).length)

onMounted(async () => {
  try {
    const [ds, cs]: any[] = await Promise.all([getDrafts(taskId), listCategory()])
    drafts.value = ds
    categories.value = cs
  } finally { loading.value = false }
})

async function saveDraft(d: any) {
  await updateDraft(d.id, {
    title: d.title, categoryName: d.categoryName, source: d.source,
    selected: d.selected, qType: d.qType,
  })
}

function toggleAll(v: boolean) {
  drafts.value.forEach(d => { d.selected = v ? 1 : 0 })   // 展示层先切；确认前统一落库
}

async function confirmAll() {
  if (!selectedCount.value) { ElMessage.warning('至少勾选一条'); return }
  submitting.value = true
  try {
    await ElMessageBox.confirm(`确认将 ${selectedCount.value} 条草稿导入题库？`, '确认导入', { type: 'info' })
    // 先把勾选态落库（防止只在前端改过）
    for (const d of drafts.value) await saveDraft(d)
    const r: any = await confirmImport(taskId)
    ElMessage.success(`导入完成：成功 ${r.success}，失败 ${r.fail}`)
    router.push('/question/list')
  } finally { submitting.value = false }
}

async function discard() {
  await ElMessageBox.confirm('放弃后草稿将被清空（题库不受影响），继续？', '放弃导入', { type: 'warning' })
  await discardImport(taskId)
  ElMessage.info('已放弃')
  router.push('/question/list')
}
</script>

<template>
  <div class="head">
    <div>
      <h3 style="margin:0">导入预览确认</h3>
      <p class="soft">共 {{ drafts.length }} 条，已勾选 {{ selectedCount }} 条。修改会自动保存到草稿。
        强识别=规则明确；弱识别=裸标题启发式，重点复核；散记=无法归属的原文兜底。</p>
    </div>
    <div class="ops">
      <el-button size="small" @click="toggleAll(true)">全选</el-button>
      <el-button size="small" @click="toggleAll(false)">全不选</el-button>
      <el-button type="danger" plain size="small" @click="discard">放弃导入</el-button>
      <el-button type="primary" :loading="submitting" @click="confirmAll">
        确认导入（{{ selectedCount }}）
      </el-button>
    </div>
  </div>

  <el-skeleton v-if="loading" :rows="8" animated />

  <div v-else class="list">
    <el-card v-for="d in drafts" :key="d.id" class="item" shadow="never" :class="{ skipped: !d.selected }">
      <div class="row">
        <el-checkbox v-model="d.selected" :true-value="1" :false-value="0" @change="saveDraft(d)" />
        <el-input v-model="d.title" class="title" @blur="saveDraft(d)" />
        <el-tag :type="ruleType[d.ruleMatched] ?? 'info'" effect="plain" size="small">
          {{ ruleName[d.ruleMatched] ?? d.ruleMatched }}
        </el-tag>
        <el-select v-model="d.categoryName" filterable allow-create clearable
          placeholder="分类" style="width: 160px" @change="saveDraft(d)">
          <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.name" />
        </el-select>
      </div>
      <el-collapse>
        <el-collapse-item title="查看答案内容">
          <MdPreview :modelValue="d.answerMd" previewTheme="github" code-theme="github" />
        </el-collapse-item>
      </el-collapse>
    </el-card>
  </div>
</template>

<style scoped>
.head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; margin-bottom: 14px }
.soft { color: var(--ink-soft); font-size: 12px; line-height: 1.9; margin: 6px 0 0 }
.ops { display: flex; gap: 8px; flex-shrink: 0 }
.list { display: flex; flex-direction: column; gap: 10px }
.item { border-radius: 12px; border: 1px solid var(--line) }
.item.skipped { opacity: .5 }
.row { display: flex; align-items: center; gap: 10px }
.title { flex: 1 }
</style>
```

**路由追加**（`router/index.ts` children）：

```ts
{ path: 'import', component: () => import('../views/ImportView.vue') },
{ path: 'import/preview/:taskId', component: () => import('../views/ImportPreviewView.vue') },
```

**侧栏入口**（HomeView，`+ 新建分类` 下方）：

```vue
<el-button class="add-cat" size="small" text type="warning" @click="router.push('/import')">导入 docx</el-button>
```

## 🧪 测试与验收

| # | 用例 | 期望 |
|---|---|---|
| T1 | 上传 3MB 的《测开八股整理》 | 秒回 taskId；t_import_task=0→1 |
| T2 | SSE 事件 | 收到 progress；status=2 时前端跳预览 |
| T3 | 草稿分布核对 | 强识别≈①/Q 数量；弱识别>0（裸标题被抓到）；至少 1 条散记（如"TCP/IP四层网络结构："前言） |
| T4 | 散记内容完整性 | 散记条目含原段落与图片 URL，图片可打开 |
| T5 | 预览页编辑 | 改标题/分类、取消勾选 → 刷新页面后状态仍在（selected 落库） |
| T6 | 确认导入 | 仅勾选条目入题库；success+fail=total；任务 status=4；草稿表清空 |
| T7 | 重复确认 | 再点确认 → 400「不在待确认状态」 |
| T8 | 放弃导入 | 草稿清空；status=5；题库无新增 |
| T9 | 幂等上传 | 同文件再传 → 返回同一 taskId，不重复解析 |
| T10 | 消费幂等 | 手工重投消息 → 日志"任务非排队态，跳过"，无重复草稿 |
| T11 | 越权 | B 账号 GET A 的 drafts/confirm → 404 |
| T12 | broker 停止时上传 | 接口仍返回 taskId；任务最终 5 且 errorMsg 有原因 |
| T13 | 手撕文档 | 单格表格全部转 ```java 代码块（抽查 3 题） |

**验收清单**：
- [ ] 13 条全绿
- [ ] 两份真实 docx 走完整链路（解析→预览→确认→题库）
- [ ] 预览页弱识别条目人工复核后无"碎尸"题（连续段落被拆碎）
- [ ] IDEA 控制台见「解析完成 taskId=… drafts=…」「消费者启动」
- [ ] commit：`feat(phase4): docx导入管线(草稿预览确认+MQ+SSE)`

> **债务登记**：
> 1. 确认导入逐题一个事务（`questionService.create`）；大批量可改批量（第 7 章压测量化）
> 2. 确认后草稿全清 → 不支持"部分导入分几次"（v1 简化；要支持需保留草稿并加批次标记）
> 3. SSE token 走 URL（日志泄露风险）→ 第 8 章部署改 cookie/短时签名
> 4. q_type 不自动识别"手撕"（预览页可手改）
> 5. 图片孤儿（放弃/失败任务已落盘的图不清理）→ 第 9 章部署前补清理任务
> 6. 弱规则参数（40 字/标点集）为经验值，后续可按你的文档集调优（写进 T 用例观察项）

## 📦 本章交付物

后端：`importjob/{entity,mapper,parser,consumer,service,controller}` + `RocketMQConfig` + `SseConfig` + CategoryService.getOrCreate + LoginInterceptor token 兜底 + properties 两项；两张新表。
前端：`api/import.ts` + `ImportView.vue` + `ImportPreviewView.vue` + 两条路由 + 侧栏入口。
依赖：poi-ooxml / rocketmq-client（已装）。

## 🔗 关联八股

- **MQ 三问**：生产可靠性（同步 send 等 ack，失败落终态）、消费幂等（CAS）、重试策略（何时该/不该 RECONSUME_LATER——半途失败卡死陷阱）
- **ThreadLocal 两处必修**：消费者线程 / SSE 线程；池化线程复用必须 set→clear
- SSE vs 轮询 vs WebSocket；EventSource 不能带头 → token 走 query 的取舍
- POI/XWPF：`IBodyElement` 保序、图片从 run 取、单格表格=代码的领域约定
- SHA-256 内容指纹做幂等键；状态机六态与终态不可逆；CAS 防并发/防重投
- **产品视角**：为什么"机器拆 + 人确认"比"纯自动"更适合个人知识库（信任分层 rule_matched 的设计）

## 下章预告

**06 · FSRS 复习系统（Phase 5）**：题库有了、导入通了，接下来让题目"会自己安排复习"——FSRS 调度、卡片状态机、签到 BitMap、打卡榜 ZSet。届时每日一条复习流就是你的真实使用入口。
