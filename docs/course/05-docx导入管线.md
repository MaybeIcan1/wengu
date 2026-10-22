# 05 · docx 导入管线（Phase 4）

> 状态：⬜ 待开发 ｜ **v3（2026-10-22 增补：解析三段式 Block→Segmenter→Assembler，为 LLM 拆题预留接口；LLM 实装与选型评测放第 9 章）** ｜ v2：人工预览确认 + 散记兜底
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
   → 消费者：CAS 0→1 → Block提取→Segmenter拆题(强/弱/散记) → 装配 → 写草稿表 → status=2 待确认
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
                    ─ BlockExtractor 抽块 → Segmenter 定边界 → Assembler 装配 → 逐条 INSERT t_import_draft
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
| 3️⃣ 业务编排 | BlockExtractor + Segmenter(RuleSegmenter) + QuestionAssembler + ImportServiceImpl + MQ | 命令行跑一次能产出草稿 |
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
  rule_matched  VARCHAR(10)  NOT NULL COMMENT 'STRONG/WEAK/SCATTER，预留 LLM_HIGH/LLM_LOW',
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

### 3.1 把"拆题"拆开写：先抽块，再决策，最后装配（v3，为 LLM 预留插槽）

**① 我们现在要干嘛**

上一层已经把任务排进队列、文件落盘、消费者就位。现在要解决本章最核心的业务问题：**一份 3MB 的 docx，怎么变成 N 条可确认的草稿？**

直觉做法是写一个 `DocxParser.parse()`，把"读段落、判标题、收表格、拼答案、兜散记"全塞进去（v2 版本就是如此）。它能跑，但你已经能预见两个会疼的地方：

- **将来要接 LLM 拆题**：只能在那几百行里塞 if-else，规则与模型逻辑纠缠——测不了、关不掉、回滚难
- **要调规则参数**：段落搬运和"什么算一道题"的判断混在一起，改一个阈值都怕碰坏抽取

**② 设计思路（两个候选对比）**

| 方案 | 长相 | 问题 |
|---|---|---|
| A. 单类解析器 | `parse(docx)` 内完成全部 | 换策略=改核心；无法单测决策；LLM 只能硬塞 |
| **B. 三层流水线（本章采用）** | 抽取（确定性）→ 决策（可替换）→ 装配（纯拼接） | 多 4 个小类，但每层可单独测、可单独换 |

选 B 的三个理由：

1. **LLM 未来只碰中间那层**——它输出的是 `[{titleIndex:13,end:16},…]` 这种**索引级决策**，物理上无法改写你的八股原文（比在提示词里写"不要改动内容"可靠得多）
2. 换策略（规则 → 本地模型 → 云端决策模型）不动抽取与装配，是"策略模式"的教科书落地点
3. 规则版可以**不依赖 MQ 单独验收**：给它一段 docx，看拆出来的草稿质量，不满意现在就能调

```
POI 原始文档
   ↓ BlockExtractor（确定性：段落/表格/代码/图片 → 带序号的块列表）
List<Block>
   ↓ Segmenter（决策层：只回答"哪几块组成一道题"）
List<Segment>
   ↓ QuestionAssembler（装配层：按索引把原文拼成草稿，逐字不改）
List<ParsedQuestion> → t_import_draft
```

**③ 缺什么（文件清单，全部新建在 `importjob/parser/` 包）**

| 文件 | 角色 | 本层结束系统能干什么 |
|---|---|---|
| `Patterns.java` | 正则/判定符号集中管理 | 两处共用一套规则，不漂移 |
| `Block.java` | 文档最小结构单元（序号/类型/原文） | 文档变成可寻址的序列 |
| `BlockExtractor.java` | POI → List\<Block\> | 任意 docx 都能被结构化 |
| `Segment.java` | 决策产物：索引区间 + 分类 + 依据 | 边界可被表达、可被校验 |
| `Segmenter.java` | 决策抽象接口 | 规则/LLM 可互换 |
| `RuleSegmenter.java` | 规则实现（强/弱/散记） | 不接 LLM 也能拆题 |
| `QuestionAssembler.java` | 按 Segment 拼原文 | 草稿内容 100% 来自原文 |
| `ParsedQuestion.java` | 装配结果（沿用 v2） | 可直接写草稿表 |

> 结构动作：在 `importjob` 下确认已有 `parser` 子包（第 0 步建过五包）；没有就右键 `importjob` → New → Package → `parser`。

**④ 代码（按依赖顺序：词汇 → 形状 → 抽取 → 决策 → 装配）**

#### 3.1.1 先建共享词汇表：`Patterns`

为什么第一步是它？因为"强规则识别"和"标题清洗"分属 Segmenter 与 Assembler 两层，**如果各写一份正则，改一处忘一处必然漂移**。集中成常量类，是零成本的防腐。

```java
// importjob/parser/Patterns.java —— 正则集中管理（Segmenter 与 Assembler 共用）
package com.wengu.backend.importjob.parser;

import java.util.List;
import java.util.regex.Pattern;

public final class Patterns {
    /** 强规则：① / 1. / Q： */
    public static final Pattern Q_START =
            Pattern.compile("^(?:[①-⑳]|[0-9]{1,2}\\s*[.、．)）]|Q\\s*[:：])");
    public static final Pattern TITLE_PREFIX =
            Pattern.compile("^(?:[①-⑳]|[0-9]{1,2}\\s*[.、．)）]|Q\\s*[:：])\\s*");
    public static final Pattern SOURCE = Pattern.compile("【([^】]{1,50})】");
    /** 弱标题禁止的结尾标点（句子特征） */
    public static final String BAD_END = "：:。，,、；;！!？?”\"'）)";
    public static final List<String> BULLETS = List.of("-", "·", "•", "*", "+", ">");

    private Patterns() {}
}
```

#### 3.1.2 确定数据形状：`Block`

为什么不是直接传 `XWPFParagraph`？因为**决策层不该知道 POI**——将来 LLM 方案里，送进去的是"块摘要"，装配时用的是"块全文"，两者都只需要 `index/kind/text` 三个字段。把 POI 对象挡在抽取层内部，是让后面两层可测的前提。

```java
// importjob/parser/Block.java —— 文档的最小结构单元
package com.wengu.backend.importjob.parser;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Block {
    public static final String HEADING = "HEADING";
    public static final String PARA = "PARA";
    public static final String CODE = "CODE";
    public static final String TABLE = "TABLE";
    public static final String IMAGE = "IMAGE";

    private int index;      // 文档内序号（Segmenter 决策的依据）
    private String kind;    // 上面五个常量之一
    private String text;    // 段落原文 / 代码围栏 / markdown表格 / 图片markdown
}
```

#### 3.1.3 做确定性抽取：`BlockExtractor`

它只回答一个问题：**按文档顺序，把内容变成块**。三个细节：
- `IBodyElement` 保序遍历（你若分开遍历段落和表格，代码块会全跑到答案末尾——第 5 章验收会查）
- 图片**独立成块**：这样将来 LLM 能判断"这张图属于上一题还是下一题"，而不是被硬塞进段落
- 单格表格 → CODE 块（你手撕文档的存储约定）；多格表格 → MARKDOWN 表

```java
// importjob/parser/BlockExtractor.java —— POI → Block 列表（确定性，无业务决策）
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

@Slf4j
@Component
public class BlockExtractor {

    @Value("${wengu.media-dir}")
    private String mediaDir;

    public List<Block> extract(InputStream in) throws IOException {
        List<Block> blocks = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String text = p.getText() == null ? "" : p.getText().trim();
                    if (!text.isEmpty()) {
                        blocks.add(new Block(blocks.size(),
                                isHeading(p, text) ? Block.HEADING : Block.PARA, text));
                    }
                    for (String url : extractImages(p)) {           // 图片独立成块（便于后续判归属）
                        blocks.add(new Block(blocks.size(), Block.IMAGE,
                                "![](" + url + ")\n\n"));
                    }
                } else if (el instanceof XWPFTable t) {
                    if (isSingleCell(t)) {
                        blocks.add(new Block(blocks.size(), Block.CODE,
                                "```java\n" + t.getRow(0).getCell(0).getText().trim() + "\n```\n"));
                    } else {
                        blocks.add(new Block(blocks.size(), Block.TABLE, toMarkdownTable(t)));
                    }
                }
            }
        }
        return blocks;
    }

    private boolean isHeading(XWPFParagraph p, String text) {
        String style = p.getStyle();
        if (style != null) {
            String s = style.toLowerCase();
            if (s.contains("heading") || style.contains("标题")) return true;
        }
        return text.startsWith("#");
    }

    private boolean isSingleCell(XWPFTable t) {
        return t.getRows().size() == 1 && t.getRow(0).getTableCells().size() == 1;
    }

    private String toMarkdownTable(XWPFTable t) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = t.getRows();
        for (int i = 0; i < rows.size(); i++) {
            List<XWPFTableCell> cells = rows.get(i).getTableCells();
            sb.append("|");
            for (XWPFTableCell c : cells) sb.append(" ").append(c.getText().replace("\n", " ").trim()).append(" |");
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
}
```

> ✅ **本小步可独立验收**：此时写个临时 `main`（或单测）把一份真实 docx 喂给 `BlockExtractor`，打印块列表——分类标题、题目行、表格、图片应各就各位。**不要等全链路通了才发现抽块就错了。**

#### 3.1.4 定义决策契约：`Segment` + `Segmenter`

关键约定：**决策层只输出索引，不输出内容**。这是 LLM 方案能安全插入的根基——模型最多把边界定错，永远改不了你的笔记正文。同时把"区间必须升序、不重叠、不越界"写进接口文档，因为将来 LLM 输出的 JSON 必须过这道校验。

```java
// importjob/parser/Segment.java —— 决策产物：只描述"索引区间 + 分类 + 依据"
package com.wengu.backend.importjob.parser;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Segment {
    private int titleIndex;          // 标题所在块
    private int endIndexInclusive;   // 该题最后一块的索引（含）
    private String category;         // 所属分类（来自最近的 HEADING）
    private String rule;             // STRONG / WEAK / SCATTER ；预留 LLM_HIGH / LLM_LOW
}
```

```java
// importjob/parser/Segmenter.java —— 决策层抽象（未来 LLM/本地分类器都实现它）
package com.wengu.backend.importjob.parser;

import java.util.List;

public interface Segmenter {
    /**
     * 只做"边界决策"：输入块序列，输出分段（索引区间），绝不修改任何文本内容。
     * 实现约定：Segment 必须按 titleIndex 升序、区间不重叠、索引在 [0, blocks.size()-1]。
     */
    List<Segment> segment(List<Block> blocks);
}
```

#### 3.1.5 落实现有规则：`RuleSegmenter`

它把 v2 的三档规则搬进决策层，行为完全等价。算法四步（先讲清再贴码）：

1. **遇 HEADING**：先给"当前题""当前散记"收尾，再切换分类
2. **遇强规则**（①/数字/Q：开头）：收尾上一个，开新题（rule=STRONG）
3. **遇弱规则**（分类内 + 当前题已有内容 + 短行无句读无逗号 + 非列表符号）：开新题（rule=WEAK）
4. **其余**：有当前题就归它；没有就归散记区（SCATTER）——**散记区与当前题互斥**，所以不会重复收纳

```java
// importjob/parser/RuleSegmenter.java
package com.wengu.backend.importjob.parser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "wengu.import.segmenter", havingValue = "rule", matchIfMissing = true)
public class RuleSegmenter implements Segmenter {

    @Override
    public List<Segment> segment(List<Block> blocks) {
        List<Segment> out = new ArrayList<>();
        String category = null;
        String currentRule = null;
        int currentStart = -1;      // 当前题的标题块
        int scatterStart = -1;      // 当前散记区起点（与 currentStart 互斥）

        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);

            if (Block.HEADING.equals(b.getKind())) {                  // 新分类：先收尾
                flush(out, currentStart, i - 1, category, currentRule);
                flush(out, scatterStart, i - 1, category, "SCATTER");
                category = b.getText().replaceAll("^#+\\s*", "").trim();
                currentStart = -1; scatterStart = -1; currentRule = null;
                continue;
            }

            boolean strong = Block.PARA.equals(b.getKind())
                    && Patterns.Q_START.matcher(b.getText()).find();
            // 弱规则：分类内、当前题已有答案、行短且无句读/逗号、非列表符号开头
            boolean weak = Block.PARA.equals(b.getKind()) && currentStart >= 0
                    && i - 1 > currentStart                            // 当前题已有内容
                    && isWeakTitle(b.getText());

            if (strong || weak) {
                flush(out, currentStart, i - 1, category, currentRule);
                flush(out, scatterStart, i - 1, category, "SCATTER");
                currentStart = i;
                currentRule = strong ? "STRONG" : "WEAK";
                scatterStart = -1;
            } else if (currentStart < 0 && scatterStart < 0) {
                scatterStart = i;                                      // 无归属内容 → 散记区
            }
            // 其余情况：内容自然落进当前题或当前散记区
        }
        flush(out, currentStart, blocks.size() - 1, category, currentRule);
        flush(out, scatterStart, blocks.size() - 1, category, "SCATTER");
        return out;
    }

    private void flush(List<Segment> out, int start, int end, String category, String rule) {
        if (start < 0 || end < start || rule == null) return;
        out.add(new Segment(start, end, category, rule));
    }

    private boolean isWeakTitle(String text) {
        if (text.isEmpty() || text.length() > 40) return false;
        if (text.contains("，") || text.contains("、") || text.contains(",")) return false;
        if (Patterns.BAD_END.indexOf(text.charAt(text.length() - 1)) >= 0) return false;
        for (String b : Patterns.BULLETS) if (text.startsWith(b)) return false;
        return true;
    }
}
```

#### 3.1.6 做纯装配：`QuestionAssembler`

**它不做任何判断**——只按 Segment 给的区间把块文本拼起来。两个必要的特殊处理：
- 非散记题的**标题块不入答案**（标题已在 `title` 字段，重复会脏）
- 散记的标题是合成的 `散记 · 分类名`，内容**包括首块**（首块就是被兜底的正文）

```java
// importjob/parser/QuestionAssembler.java —— 装配层：按索引拼原文，逐字不改
package com.wengu.backend.importjob.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

@Component
public class QuestionAssembler {

    public List<ParsedQuestion> assemble(List<Block> blocks, List<Segment> segments) {
        List<ParsedQuestion> out = new ArrayList<>();
        for (Segment s : segments) {
            ParsedQuestion q = new ParsedQuestion();
            q.setRule(s.getRule());
            q.setCategory(s.getCategory());

            if ("SCATTER".equals(s.getRule())) {
                q.setTitle(truncate("散记 · "
                        + (s.getCategory() == null ? "未分类" : s.getCategory()), 100));
            } else {
                String raw = blocks.get(s.getTitleIndex()).getText();
                Matcher m = Patterns.SOURCE.matcher(raw);
                if (m.find()) q.setSource(m.group(1));
                String t = Patterns.TITLE_PREFIX.matcher(raw).replaceFirst("").trim();
                q.setTitle(truncate(t.isEmpty() ? raw : t, 100));
            }

            StringBuilder ans = new StringBuilder();
            for (int i = s.getTitleIndex(); i <= s.getEndIndexInclusive() && i < blocks.size(); i++) {
                if (!"SCATTER".equals(s.getRule()) && i == s.getTitleIndex()) continue;  // 标题行不入答案
                String text = blocks.get(i).getText();
                ans.append(text);
                if (!text.endsWith("\n")) ans.append("\n\n");
            }
            if (ans.length() == 0) ans.append("（空）\n");
            q.setAnswer(ans);
            out.add(q);
        }
        return out;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

#### 3.1.7 沿用装配结果：`ParsedQuestion`

与 v2 相同，只是 `rule` 的取值空间扩展为五档（后两档第 9 章启用）：

```java
// importjob/parser/ParsedQuestion.java
package com.wengu.backend.importjob.parser;

import lombok.Data;

@Data
public class ParsedQuestion {
    public static final String STRONG = "STRONG";
    public static final String WEAK = "WEAK";
    public static final String SCATTER = "SCATTER";
    // 预留：LLM_HIGH（模型高置信边界）/ LLM_LOW（低置信，预览页重点标黄）

    private String title;
    private String category;
    private String source;
    private String rule;
    private StringBuilder answer = new StringBuilder();
}
```

> ✅ **本小步可独立验收**：`BlockExtractor → RuleSegmenter → QuestionAssembler` 三段串起来，打印 `ParsedQuestion` 列表，直接肉眼判断拆分质量（强/弱/散记分布、有没有把连续段落拆碎）。这一步不满意，就调弱规则参数——**别等 MQ、SSE、前端全做完再回来改**。

> ★ 抽问五连：
> 1. 为什么让 LLM 只输出 `Segment`（索引），而不是让它输出整理好的题目 JSON？（答：索引决策无法改写原文——从架构上消灭"模型润色笔记"的幻觉风险）
> 2. 为什么散记区与当前题互斥？（答：有当前题时所有块默认归属它；散记只收"无人认领"的开头区，避免重复收纳）
> 3. 弱规则为什么要求"当前题已有内容"？（答：否则一道题开头的连续短行会被全部拆碎）
> 4. 弱标题为什么禁逗号/顿号、禁句读结尾？（答：最便宜的"句子 vs 标题"判别器，零成本过滤大多数误判）
> 5. 单格表格为什么判为代码块？（答：你手撕文档的存储习惯，领域约定）

**Phase 9 的 LLM 落地方式（本章只留接口，不写实现）**：

| 方案 | 说明 | 选型结论 |
|---|---|---|
| **Ollama + Qwen（7B/8B）本地** | 逐块问"这块是题目标题吗/属于哪类"，输出 JSON 后校验；中文原生、离线、免费 | **主力**（第 9 章实装 `LlmSegmenter`） |
| 本地小分类器（BERT/embedding+LR） | 标注 100~300 个块训练，毫秒级；只做"廉价闸门" | 可选优化（第 9 章评测后决定） |
| **Jev（TypeSafe System One）** | 不生成文本，只能答 Choice/Noul/Score；逐块 Noul 判断 + 校准置信度，可做"高置信自动/低置信标黄"；$0.042/M 输入、输出免费、70~500ms；但**中文弱于英文**、闭源托管、国内需代理 | **仅进选型评测对照**，不作为运行依赖 |

配置预留（`application.properties`，本章只用到 `rule`）：

```properties
# 拆题策略：rule（默认）| llm（第9章）| hybrid（第9章，规则定强边界 + LLM 判模糊区）
wengu.import.segmenter=rule
```

`rule_matched` 五档语义（前三个本章使用，后两个第 9 章启用）：

| 值 | 含义 | 预览页处理 |
|---|---|---|
| STRONG | 强规则命中 | 正常展示 |
| WEAK | 裸标题启发式 | 可折叠提示"请复核" |
| SCATTER | 无归属兜底 | 提示"散记，需人工整理" |
| LLM_HIGH（预留） | 模型高置信边界 | 正常展示 |
| LLM_LOW（预留） | 模型低置信边界 | **标黄，重点复核** |

### 3.2 MQ 接线（raw client）

**① 我们现在要干嘛**：上传接口必须"秒回 taskId"，真正的解析要挪到后台——因此需要一个**可靠投递**的通道，把"任务Id + 用户Id"交给消费者。

**② 设计思路（三条决策）**：
1. **不用 `rocketmq-spring-boot-starter`**：Boot 4 生态太新，starter 自动装配有版本风险（MyBatis-Plus 的亏吃过一次）。raw client 多写 30 行，换来可控与可讲
2. **消息体带 `userId`**：消费者线程没有 HTTP 上下文，多租户插件每个 SQL 都要 `user_id` → 身份必须随消息走（`taskId:userId`）
3. **发送必须同步等 ack**：发送失败就把任务置 5（终止），绝不能让前端拿到一个永远排队的 taskId

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| RocketMQConfig.java | `config/`（第 1 章就有这个包） | 生产者 Bean（启动即连 namesrv） |
| ImportTaskConsumer.java | `importjob/consumer/`（新建子包） | 消费者：订阅 topic，失败**不重投**（防卡死，见代码注释） |
| application.properties | 追加两项 | namesrv 地址 + 导入目录 |


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

**① 我们现在要干嘛**：把前面所有零件串成业务用例——上传、解析成草稿、列草稿、改草稿、确认转正、放弃。这是本章"业务编排层"的主干。

**② 设计思路（先接口后实现）**：
- **接口描述用例，不暴露实现细节**：`upload / parseToDrafts / markFailed / listDrafts / updateDraft / confirm / discard`——每个方法对应用户（或消费者）的一次意图
- **事务边界**：`confirm` 里**逐题**调 `questionService.create()`（单题事务，一题失败不连坐）；任务状态更新独立于题目事务（否则一次失败全部回滚、任务状态还停在 3）
- **上下文三处铁律**：`parseToDrafts` / `confirm` / `discard` 都是"可能脱离 HTTP 线程"的入口 → 用 try/finally 包 `UserContext.set/clear`

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| ImportService.java | `importjob/service/` | 业务接口（7 个用例方法） |
| ImportServiceImpl.java | 同包 | 全部实现（本章最长的一个类） |
| CategoryService.getOrCreate | **改既有文件**（`question/service/`，接口加 1 行 + 实现加 10 行） | 导入时按分类名"不存在就建" |


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
import com.wengu.backend.importjob.parser.Block;
import com.wengu.backend.importjob.parser.BlockExtractor;
import com.wengu.backend.importjob.parser.ParsedQuestion;
import com.wengu.backend.importjob.parser.QuestionAssembler;
import com.wengu.backend.importjob.parser.Segment;
import com.wengu.backend.importjob.parser.Segmenter;
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

    private final BlockExtractor blockExtractor;
    private final Segmenter segmenter;
    private final QuestionAssembler questionAssembler;
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
                List<Block> blocks = blockExtractor.extract(in);        // ① 结构化
                List<Segment> segments = segmenter.segment(blocks);     // ② 定边界（可换 LLM 实现）
                parsed = questionAssembler.assemble(blocks, segments);  // ③ 装配原文
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

**① 我们现在要干嘛**：把业务层暴露成 HTTP 接口，并且给前端一条**实时进度**通道（SSE）。业务逻辑一条都不许写在 Controller 里——它只做"收参 → 调 Service → 包 Result"。

**② 设计思路**：
- 7 个端点：上传 / 任务查询 / 草稿列表 / 改草稿 / 确认 / 放弃 / SSE 进度
- SSE 需要独立线程池（不能占用 Tomcat 的业务线程池），因此加一个 `sseExecutor` Bean
- **SSE 线程同样没有 HTTP 上下文** → 用请求线程先取 `userId`，进线程后 `set`，finally `clear`（与消费者同一考点，第二次踩）
- `EventSource` 不能自定义请求头 → 登录拦截器对 token 增加 "query 参数兜底"

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| SseConfig.java | `config/` | SSE 专用线程池 |
| ImportController.java | `importjob/controller/` | 7 个端点 + SSE 推送循环 |
| LoginInterceptor.java | **改既有文件**（`security/`） | token 支持 `?token=`（只补两行） |


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
# 拆题策略：rule（本章默认）| llm / hybrid（第9章 LLM 实装后启用）
wengu.import.segmenter=rule
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

**① 我们现在要干嘛**：给用户一个"拖进去 → 看进度 → 在预览里把关 → 确认入库"的完整闭环。

**② 设计思路（两个页面分工）**：
- `ImportView` 只负责"上传 + 等解析"：上传后订阅 SSE，看到 status=2（草稿就绪）**立刻跳转**——不在上传页做编辑
- `ImportPreviewView` 只负责"人工关卡"：改标题/分类、勾选、折叠看答案、确认/放弃。所有修改**即时落库**（防止刷新丢勾选），确认前再统一保存一遍兜底
- 数据流与第 3 章保持一致：id 全字符串、请求走统一 `request.ts`（唯 FormData 除外）

**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| api/import.ts | `frontend/src/api/` | 6 个接口翻译（含 FormData 上传） |
| ImportView.vue | `frontend/src/views/` | 上传 + SSE 进度 |
| ImportPreviewView.vue | 同目录 | 预览确认页（本章第二大页面） |
| router/index.ts | **改既有文件** | 加 `import` 与 `import/preview/:taskId` 两条子路由 |
| HomeView.vue | **改既有文件** | 侧栏加"导入 docx"入口 |


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
> 7. **LLM 拆题未实装**（v3 只留接口）：第 9 章做 `LlmSegmenter`（本地 Ollama + Qwen 主力；Jev 仅作对照评测）——届时 `rule_matched` 启用 LLM_HIGH/LLM_LOW 两档，预览页对 LLM_LOW 标黄

## 📦 本章交付物

后端：`importjob/{entity,mapper,parser,consumer,service,controller}`（parser 含 Block/BlockExtractor/Segment/Segmenter/RuleSegmenter/QuestionAssembler/Patterns/ParsedQuestion）+ `RocketMQConfig` + `SseConfig` + CategoryService.getOrCreate + LoginInterceptor token 兜底 + properties 三项；两张新表。
前端：`api/import.ts` + `ImportView.vue` + `ImportPreviewView.vue` + 两条路由 + 侧栏入口。
依赖：poi-ooxml / rocketmq-client（已装）。

## 🔗 关联八股

- **MQ 三问**：生产可靠性（同步 send 等 ack，失败落终态）、消费幂等（CAS）、重试策略（何时该/不该 RECONSUME_LATER——半途失败卡死陷阱）
- **ThreadLocal 两处必修**：消费者线程 / SSE 线程；池化线程复用必须 set→clear
- SSE vs 轮询 vs WebSocket；EventSource 不能带头 → token 走 query 的取舍
- POI/XWPF：`IBodyElement` 保序、图片从 run 取、单格表格=代码的领域约定
- SHA-256 内容指纹做幂等键；状态机六态与终态不可逆；CAS 防并发/防重投
- **策略模式**：Block 抽取（确定性）/ Segmenter（可替换决策）/ Assembler（纯装配）——LLM 只替换中间一层，从架构上隔离"模型改写内容"的风险
- **决策模型 vs 生成模型选型**（第 9 章展开）：Jev（TypeSafe System One）只能答 Choice/Noul/Score、输出 token 免费、$0.042/M 输入、70~500ms，但不生成文本、中文弱于英文、闭源需代理；本地 Ollama+Qwen 中文更强、离线免费 → **个人知识库场景选本地**
- **产品视角**：为什么"机器拆 + 人确认"比"纯自动"更适合个人知识库（信任分层 rule_matched 的设计）

## 下章预告

**06 · FSRS 复习系统（Phase 5）**：题库有了、导入通了，接下来让题目"会自己安排复习"——FSRS 调度、卡片状态机、签到 BitMap、打卡榜 ZSet。届时每日一条复习流就是你的真实使用入口。