# 05 · docx 导入管线（Phase 4）

> 状态：⬜ 待开发 ｜ v1（2026-10-21）
> 本章是**并发战场的第一仗**：上传不再同步处理，而是"接口秒回 + 队列削峰 + 后台解析 + 进度推送"。做完后你 `D:\秋招\后端八股\` 的两份真实文档可以一键入库。

---

## 🎯 本章目标与「我需要什么」

**业务需求（讲人话）**：你现在有一堆积累的 docx（测开八股整理 3MB、手撕思路），想一次性导进 wengu。但如果这活放在 HTTP 请求里同步干：解析 3MB 文档+上百张图要 1-2 分钟 → 浏览器转圈到超时、连接被 Tomcat 掐断、用户狂点上传造成重复解析。**所以必须异步**。

**目标链路**：
```
上传(秒回taskId) → 存盘+哈希去重 → 入库任务(排队) → 发MQ → 消费者解析入库 → 刷新计数(status=2)
                                                    ↘ 前端 SSE 实时看进度
```

**需要**：Phase 0~3 全绿；Docker 容器（MySQL/Redis/**RocketMQ**）在线；两个真实 docx 备好。

**依赖（我来装，你只改代码）**：`poi-ooxml:5.3.0`（解析 docx）、`rocketmq-client:5.3.1`。
> 💡 为什么不用 `rocketmq-spring-boot-starter`：Boot 4 生态太新，starter 的自动装配版本风险大（你已经在 MyBatis-Plus 上吃过一次"GAV 没错但类搬家"的亏）。**raw client 手动封装**——多写 30 行，换来完全掌控，面试时还能讲"为什么不用 starter"。

## 0️⃣ 本章全景：一次导入的完整旅程

```
① 用户拖入 test.docx
② POST /import/docx ──> 校验后缀/大小 → 存 data/import/yyyyMM/uuid.docx
                     ──> SHA-256 算指纹 → 查重（同用户同指纹且未失败 → 直接返回旧taskId，幂等）
                     ──> INSERT t_import_task(status=0 排队)
                     ──> 发 MQ 消息 {taskId} ──> 立即返回 taskId   ← 毫秒级响应
③ RocketMQ 投递 ──> 消费者线程（无 HTTP 上下文！）
                     ──> CAS status 0→1（防重复消费）
                     ──> 装载 UserContext（★ 必须，否则租户插件全部炸）
                     ──> DocxParser 解析 → 逐题 questionService.create()
                     ──> 每5题回写 success_count → 终态 status=2/3
④ 前端 EventSource 订阅 /import/task/{id}/progress?token=xxx
                     ──> 每 500ms 推 {status, total, success, fail, errorMsg}
                     ──> 终态自动断开 + 跳转题目列表
```

**本章分层定位**（每一步都在旅程上）：

| 层 | 你将产出 | 本层结束系统能干什么 |
|---|---|---|
| 1️⃣ 规格 | t_import_task 表 + ImportTask 实体 + 解析规则表 | 知道"一次导入"要记哪些字段 |
| 2️⃣ 数据层 | ImportTaskMapper（含 CAS 状态流转） | 任务状态机可独立运转 |
| 3️⃣ 业务编排 | DocxParser + ImportServiceImpl + MQ 生产/消费 | 命令行触发一次任务能跑完整 |
| 4️⃣ 接口编排 | ImportController（上传/查询/SSE） | Apifox 能拿到 taskId 与进度 |
| 5️⃣ 前端联调 | 导入页 + 进度条 + 侧栏入口 | 浏览器拖文件 → 看进度 → 题目出现 |

## 1️⃣ 规格定义层（结束时：字段与规则全部钉死）

**DDL（Navicat 执行）**：

```sql
CREATE TABLE t_import_task (
  id            BIGINT       NOT NULL,
  user_id       BIGINT       NOT NULL,
  file_name     VARCHAR(255) NOT NULL COMMENT '原始文件名',
  file_hash     CHAR(64)     NOT NULL COMMENT '文件SHA-256，幂等键',
  stored_path   VARCHAR(255) NOT NULL COMMENT '落盘相对路径 yyyyMM/uuid.docx',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0排队 1解析中 2完成 3失败',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入任务（状态机表）';
```

**字段设计理由（面试问答素材）**：
- `file_hash` + `idx_user_hash`：幂等键。同一用户重复传同一份文件 → 不重复解析。为什么不用文件名？文件名会改、内容才是身份。
- `status` 四态：终态必须区分"完成"与"失败"，否则进度页无法收尾；`0` 与 `1` 的区分让"重复消费"可被 CAS 挡掉。
- `total_count` 解析完才知道 → 先 0，解析后回填；`success/fail` 每 5 条回写一次（给 SSE 有东西可推）。
- `error_msg` 只留 500 字：完整堆栈进日志，DB 只存"给人看的原因"。

**解析规则表（对准你的两份真实文档）**：

| docx 特征 | 判定 | 处理 |
|---|---|---|
| 段落样式 Heading1/2（或文本以 `#` 开头） | 分类名 | 后续题目归入该分类；同名分类"不存在就建" |
| 段落匹配 `^[①-⑳]`、`^\d{1,2}[.、．)）]`、`^Q[:：]` | 题目标题 | 开新题；标题截断 100 字 |
| 其余非空段落 | 答案正文 | 追加到当前题（`\n` 分隔） |
| 单格表格（1行1列） | 手撕代码块 | 包成 ```` ```java ```` 围栏 |
| 多行多列表格 | 对照表 | 转 Markdown 表格 |
| 段落内嵌图片（`XWPFPicture`） | 配图 | 存 `data/media/import/yyyyMM/uuid.ext`，答案里插 `![](/media/import/…)` |
| 文本含 `【美的机考】` | 来源 | 提取进 `source` 字段 |

**实体（question 域外的独立业务域：新建包 `importjob`）**：
> 结构动作三件：**建在哪**=`com.wengu.backend.importjob.{entity,mapper,service,controller,parser}`；**怎么建**=右键 `com.wengu.backend` → New → Package → `importjob`，再建五个子包；**为什么**=导入是一个独立业务域（有自己的状态机与消费者），不该塞进 question 包——沿用"业务边界=包边界"铁律。

```java
// importjob/entity/ImportTask.java
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
    private String storedPath;     // 落盘相对路径（不塞 errorMsg 里，两回事）
    private Integer status;        // 0排队 1解析中 2完成 3失败
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

## 2️⃣ 数据访问层（结束时：任务状态机可独立驱动）

```java
// importjob/mapper/ImportTaskMapper.java
package com.wengu.backend.importjob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.backend.importjob.entity.ImportTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ImportTaskMapper extends BaseMapper<ImportTask> {

    /** ★ CAS：只有"排队中"才能进入"解析中"——重复消费的唯一天然屏障 */
    @Update("update t_import_task set status = 1, updated_time = now() " +
            "where id = #{id} and status = 0 and deleted = 0")
    int markParsing(@Param("id") Long id);
}
```

> ⚠️ 这行 UPDATE 会被**多租户插件改写**（自动追加 `user_id = ?`）→ 消费者线程必须先 `UserContext.set(...)` 才能执行。这是本章第一个 ★ 大考点，第 3 层会兑现。
> ★ 抽问：为什么用 `update ... where status=0` 而不是"先 select 再 update"？（答：check-then-act 在并发/重投下必然重复执行；CAS 把判断塞进同一条 SQL，由数据库原子性兜底——与第 3 章标签 `createIfAbsent` 是同一信仰）

## 3️⃣ 业务编排层（结束时：一次任务从"存盘"到"题目入库"全链路可跑）

### 3.1 SHA-256 与文件落盘（先做工具）

```java
// importjob/service/ImportServiceImpl.java 内的私有工具（后面整类给出）
private String sha256(byte[] bytes) {
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
    }
}
```

### 3.2 DocxParser（本章技术含量最高的类，完整代码）

```java
// importjob/parser/ParsedQuestion.java —— 解析产物（纯数据）
package com.wengu.backend.importjob.parser;

import lombok.Data;

@Data
public class ParsedQuestion {
    private String title;
    private String category;
    private String source;
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

    /** ①/Q：/1. 开头 → 新题 */
    private static final Pattern Q_START =
            Pattern.compile("^(?:[①-⑳]|[0-9]{1,2}\\s*[.、．)）]|Q\\s*[:：])");
    /** 【美的机考】 → 来源 */
    private static final Pattern SOURCE = Pattern.compile("【([^】]{1,50})】");
    /** 标题前的编号符号，进库前剥掉 */
    private static final Pattern TITLE_PREFIX =
            Pattern.compile("^(?:[①-⑳]|[0-9]{1,2}\\s*[.、．)）]|Q\\s*[:：])\\s*");

    @Value("${wengu.media-dir}")
    private String mediaDir;

    public List<ParsedQuestion> parse(InputStream in) throws IOException {
        List<ParsedQuestion> result = new ArrayList<>();
        ParsedQuestion current = null;
        String category = null;

        try (XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String text = p.getText() == null ? "" : p.getText().trim();
                    List<String> images = extractImages(p);

                    if (!text.isEmpty()) {
                        if (isHeading(p, text)) {
                            // 新分类开始：收尾上一题
                            if (current != null) { result.add(current); current = null; }
                            category = text.replaceAll("^#+\\s*", "").trim();
                        } else if (Q_START.matcher(text).find()) {
                            if (current != null) result.add(current);
                            current = new ParsedQuestion();
                            current.setCategory(category);
                            Matcher m = SOURCE.matcher(text);
                            if (m.find()) current.setSource(m.group(1));
                            String title = TITLE_PREFIX.matcher(text).replaceFirst("").trim();
                            current.setTitle(truncate(title.isEmpty() ? text : title, 100));
                        } else if (current != null) {
                            current.getAnswer().append(text).append("\n\n");
                        }
                    }
                    // 图片插在当前题的答案里（顺序与文档一致）
                    if (current != null && !images.isEmpty()) {
                        for (String url : images) {
                            current.getAnswer().append("![](").append(url).append(")\n\n");
                        }
                    }
                } else if (el instanceof XWPFTable table && current != null) {
                    if (isSingleCell(table)) {
                        current.getAnswer().append("```java\n")
                                .append(table.getRow(0).getCell(0).getText().trim())
                                .append("\n```\n\n");
                    } else {
                        current.getAnswer().append(toMarkdownTable(table)).append("\n");
                    }
                }
            }
            if (current != null) result.add(current);
        }
        return result;
    }

    /** 样式名含 Heading / 标题，或文本以 # 开头 → 分类标题 */
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

    /** 段落内嵌图片 → 落盘 → 返回可访问 URL */
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
}
```

> ★ 抽问三连：① 为什么用 `IBodyElement` 而不是分开遍历 `getParagraphs()` 和 `getTables()`？（答：分开遍历会丢顺序，代码块和对照表会跑到答案末尾）② 单格表格为什么判定为代码？（答：你的手撕文档就是这么存的）③ 图片为什么按段落地顺序插，不统一放末尾？（答：图文语境绑定）

### 3.3 MQ 接线（raw client 封装）

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
    public static final String GROUP_PRODUCER = "wengu-import-producer";
    public static final String GROUP_CONSUMER = "wengu-import-consumer";

    @Value("${wengu.rocketmq.namesrv}")
    private String namesrv;

    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer importProducer() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(GROUP_PRODUCER);
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
        consumer = new DefaultMQPushConsumer(RocketMQConfig.GROUP_CONSUMER);
        consumer.setNamesrvAddr(namesrv);
        consumer.subscribe(RocketMQConfig.TOPIC_IMPORT, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt msg : msgs) {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                String[] parts = body.split(":");
                if (parts.length != 2) {
                    log.error("非法消息体: {}", body);
                    continue;                       // 脏消息不再重试（重试也解析不了）
                }
                Long taskId = Long.valueOf(parts[0]);
                Long userId = Long.valueOf(parts[1]);
                try {
                    importService.handleMessage(taskId, userId);
                } catch (Exception e) {
                    // ★ handleMessage 内部已把异常落到任务状态(3)，这里不重投：
                    //   若 RECONSUME_LATER，重投时 CAS(markParsing) 会因 status=1 失败 → 直接跳过
                    //   → 任务永久卡在"解析中"。**幂等 + 重试还要考虑"半途失败"的状态机出口。**
                    log.error("导入消费异常（任务已置失败）taskId={}", taskId, e);
                    importService.markFailed(taskId, userId, "导入异常：" + e.getClass().getSimpleName());
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("导入任务消费者启动: topic={}", RocketMQConfig.TOPIC_IMPORT);
    }

    @Override
    public void destroy() {
        if (consumer != null) consumer.shutdown();
    }
}
```

> ★ 抽问：重投会不会导致重复入库？（答：`markParsing` 的 CAS 挡第二轮——status 已是 1/2/3 时返回 0 行，直接 return。**幂等不是"保证只消费一次"，而是"消费多次结果一致"**）

### 3.4 ImportService（编排核心，完整代码）

先补一个分类工具（在既有 CategoryService 里加方法——结构动作：打开 `question/service/CategoryService.java` 加一行，`CategoryServiceImpl` 加实现）：

```java
// CategoryService 接口新增：
Long getOrCreate(String name);

// CategoryServiceImpl 新增实现：
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

```java
// importjob/service/ImportService.java
package com.wengu.backend.importjob.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.wengu.backend.importjob.entity.ImportTask;
import org.springframework.web.multipart.MultipartFile;

public interface ImportService extends IService<ImportTask> {
    Long upload(MultipartFile file, Long userId);       // 存盘+去重+建任务+发MQ，秒回 taskId
    void handleMessage(Long taskId, Long userId);       // 消费者入口（userId 随消息传递，见 ★）
    void markFailed(Long taskId, Long userId, String reason);  // 投递/启动失败时置失败态
}
```

```java
// importjob/service/ImportServiceImpl.java
package com.wengu.backend.importjob.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.common.util.UserContext;
import com.wengu.backend.config.RocketMQConfig;
import com.wengu.backend.importjob.entity.ImportTask;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    private final QuestionService questionService;
    private final CategoryService categoryService;
    private final DefaultMQProducer importProducer;

    @Value("${wengu.import-dir}")
    private String importDir;

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

        // 幂等：同用户同内容且未失败 → 直接复用旧任务
        ImportTask exist = lambdaQuery()
                .eq(ImportTask::getFileHash, hash)
                .in(ImportTask::getStatus, 0, 1, 2)
                .last("limit 1").one();
        if (exist != null) return exist.getId();

        // 落盘：yyyyMM 分目录 + uuid 重名，防冲突/防穿越
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String stored;
        try {
            Path dir = Paths.get(importDir, month);
            Files.createDirectories(dir);
            String fileName = UUID.randomUUID() + ".docx";
            stored = month + "/" + fileName;
            Files.write(dir.resolve(fileName), bytes);
        } catch (Exception e) {
            throw new BizException(500, "文件保存失败");
        }

        ImportTask task = new ImportTask();
        task.setUserId(userId);
        task.setFileName(name.length() > 200 ? name.substring(0, 200) : name);
        task.setFileHash(hash);
        task.setStoredPath(stored);
        task.setStatus(0);
        task.setTotalCount(0); task.setSuccessCount(0); task.setFailCount(0);
        save(task);
        UserContext.clear();                    // save 后立刻清（本线程后续无租户查询）

        try {
            // ★ 消息体带 userId：消费者线程没有 HTTP 上下文，靠它恢复租户身份
            importProducer.send(new Message(
                    RocketMQConfig.TOPIC_IMPORT,
                    (task.getId() + ":" + userId).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("MQ 发送失败 taskId={}", task.getId(), e);
            markFailed(task.getId(), userId, "任务投递失败，请检查 RocketMQ 后重试");
        }
        return task.getId();
    }

    @Override
    public void handleMessage(Long taskId, Long userId) {
        // ★ 上下文顺序铁律：先装"身份"，再做任何租户查询/写库
        UserContext.set(userId);
        try {
            ImportTask task = getById(taskId);          // 现在租户插件能正确拼 user_id
            if (task == null) return;
            if (getBaseMapper().markParsing(taskId) == 0) {
                log.info("任务非排队态，跳过（幂等）taskId={}", taskId);
                return;                          // 已被处理/失败过 → 不重复解析
            }
            List<ParsedQuestion> parsed;
            try (FileInputStream in = new FileInputStream(
                    Paths.get(importDir, task.getStoredPath()).toFile())) {
                parsed = docxParser.parse(in);
            }
            int ok = 0, fail = 0, total = parsed.size();
            for (int i = 0; i < total; i++) {
                ParsedQuestion pq = parsed.get(i);
                try {
                    QuestionCreateDTO dto = new QuestionCreateDTO();
                    dto.setTitle(pq.getTitle());
                    dto.setAnswerMd(pq.getAnswer().toString());
                    dto.setCategoryId(categoryService.getOrCreate(pq.getCategory()));
                    dto.setTagNames(List.of());        // v1：导入不带标签，可后续人工补
                    dto.setQType(0);
                    dto.setSource(pq.getSource());
                    dto.setDifficulty(0);
                    questionService.create(dto);
                    ok++;
                } catch (Exception e) {
                    fail++;
                    log.warn("第{}题入库失败: {}", i + 1, pq.getTitle(), e);
                }
                if ((i + 1) % 5 == 0) {               // 进度节流回写：每5题一次
                    updateProgress(taskId, total, ok, fail);
                }
            }
            updateFinal(taskId, total, ok, fail, 2, null);
            log.info("导入完成 taskId={} total={} ok={} fail={}", taskId, total, ok, fail);
        } catch (Exception e) {
            log.error("导入失败 taskId={}", taskId, e);
            updateFinal(taskId, 0, 0, 0, 3, truncateRoot(e));
        } finally {
            UserContext.clear();                  // ★ 线程池复用必须清，否则串号
        }
    }

    @Override
    public void markFailed(Long taskId, Long userId, String reason) {
        UserContext.set(userId);
        try {
            lambdaUpdate().eq(ImportTask::getId, taskId)
                    .set(ImportTask::getStatus, 3)
                    .set(ImportTask::getErrorMsg, reason)
                    .update();
        } finally {
            UserContext.clear();
        }
    }

    private void updateProgress(Long id, int total, int ok, int fail) {
        lambdaUpdate().eq(ImportTask::getId, id)
                .set(ImportTask::getTotalCount, total)
                .set(ImportTask::getSuccessCount, ok)
                .set(ImportTask::getFailCount, fail)
                .update();
    }

    private void updateFinal(Long id, int total, int ok, int fail, int status, String err) {
        lambdaUpdate().eq(ImportTask::getId, id)
                .set(ImportTask::getStatus, status)
                .set(ImportTask::getTotalCount, total)
                .set(ImportTask::getSuccessCount, ok)
                .set(ImportTask::getFailCount, fail)
                .set(err != null, ImportTask::getErrorMsg, err)
                .update();
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

> ★ 本章头号大考点（写进笔记）：**异步线程没有 HTTP 上下文**。消费者/SSE 线程里 `UserContext` 是空的，而多租户插件每个 SQL 都要 `user_id` → 必然炸或不安全。
> 正确姿势 = **身份随消息传递**：发送端把 `taskId:userId` 一起发（上面 upload 代码已是这样）；消费者第一行 `UserContext.set(userId)`，`finally { clear() }`。
> 为什么不在消费者里 "先查任务拿 userId"？因为那次查询本身就要租户条件——先有鸡还是先有蛋。**别为了省两个字段把上下文绕丢。**
> 消息体格式约定：`"taskId:userId"`（例如 `2111833336913125377:2111832265297883138`）。

## 4️⃣ 接口编排层（结束时：Apifox 能上传、能查状态、能被 SSE 推着走）

**结构动作**：`ImportController` 放 `importjob/controller/`；SSE 需要线程池 → 新建 `config/SseConfig.java`（一个 `ThreadPoolTaskExecutor` Bean）。

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
        ex.setWaitForTasksToCompleteOnShutdown(false);
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

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @Qualifier("sseExecutor")
    private final ThreadPoolTaskExecutor sseExecutor;

    @Data
    @AllArgsConstructor
    public static class TaskVO {
        private Long id;
        private Integer status;
        private Integer totalCount;
        private Integer successCount;
        private Integer failCount;
        private String errorMsg;
        private String fileName;
    }

    @PostMapping("/docx")
    public Result<Long> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(importService.upload(file, UserContext.requireUserId()));
    }

    /** 轮询兜底接口（SSE 不可用时用） */
    @GetMapping("/task/{id}")
    public Result<TaskVO> task(@PathVariable Long id) {
        ImportTask t = importService.getById(id);
        if (t == null) throw new BizException(404, "任务不存在");
        return Result.success(toVO(t));
    }

    /**
     * SSE 进度推送：每 500ms 推一次，终态自动断开，最长 5 分钟。
     * ⚠️ EventSource 不能自定义请求头 → token 走查询参数（登录拦截器已兼容）
     */
    @GetMapping("/task/{id}/progress")
    public SseEmitter progress(@PathVariable Long id) {
        Long userId = UserContext.requireUserId();       // 请求线程里拿（SSE 线程没有）
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        sseExecutor.execute(() -> {
            UserContext.set(userId);                      // ★ 跨线程补上下文
            try {
                for (int i = 0; i < 600; i++) {           // 600 * 500ms = 5min 上限
                    ImportTask t = importService.getById(id);
                    if (t == null) { emitter.completeWithError(new BizException(404, "任务不存在")); return; }
                    emitter.send(SseEmitter.event().name("progress").data(toVO(t)));
                    if (t.getStatus() != null && t.getStatus() >= 2) { emitter.complete(); return; }
                    TimeUnit.MILLISECONDS.sleep(500);
                }
                emitter.complete();
            } catch (Exception e) {
                try { emitter.completeWithError(e); } catch (Exception ignored) { }
            } finally {
                UserContext.clear();                      // ★ 线程池复用必清
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

**登录拦截器小改**（结构动作：打开 `security/LoginInterceptor.java`，token 提取逻辑改为"头优先，查参兜底"）：

```java
String auth = req.getHeader("Authorization");
String token = null;
if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7);
if (token == null) token = req.getParameter("token");     // EventSource 只能走 query
Long userId = token == null ? null : JwtUtil.parse(token);
```

**配置追加**（`application.properties`）：

```properties
wengu.rocketmq.namesrv=localhost:9876
wengu.import-dir=D:/JavaCode/wengu/data/import
```

**路由表**：

| 方法 | 路径 | 入参 | 出参 | 说明 |
|---|---|---|---|---|
| POST | /import/docx | multipart `file` | taskId | 秒回 |
| GET | /import/task/{id} | — | TaskVO | 轮询兜底 |
| GET | /import/task/{id}/progress?token= | — | SSE 流 | 500ms 推一次 |

## 5️⃣ 前端联调层（结束时：拖文件 → 看进度 → 题目出现）

**结构动作**：新建 `frontend/src/api/import.ts` + `frontend/src/views/ImportView.vue`；改 `router/index.ts` 加子路由（`path: 'import'`）；HomeView 侧栏加一个"导入 docx"按钮。

```ts
// api/import.ts
import request from './request'

export const uploadDocx = (file: File) => {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/import/docx', fd)
}
export const getTask = (id: string) => request.get(`/import/task/${id}`)
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
const status = ref<number>(0)          // 0排队 1解析中 2完成 3失败
const total = ref(0); const ok = ref(0); const fail = ref(0)
const errorMsg = ref('')
const uploading = ref(false)
let es: EventSource | null = null

const statusText = ['排队中…', '解析中…', '导入完成', '导入失败']

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
    status.value = t.status
    total.value = t.totalCount ?? 0
    ok.value = t.successCount ?? 0
    fail.value = t.failCount ?? 0
    errorMsg.value = t.errorMsg ?? ''
    if (t.status >= 2) { es?.close(); es = null }
  })
  es.onerror = () => { es?.close(); es = null; ElMessage.error('进度连接断开，可刷新任务列表查看') }
}

onBeforeUnmount(() => { es?.close() })
</script>

<template>
  <el-card class="wrap" shadow="never">
    <h3>导入 docx 题库</h3>
    <p class="soft">支持 .docx（20MB 内）。标题/①/Q：开头识别为题目，Heading 识别为分类，单格表格转代码块，内嵌图片自动提取。</p>
    <el-upload drag :auto-upload="false" :limit="1" accept=".docx"
      :on-change="onFileChange" :on-exceed="() => ElMessage.warning('一次一个文件')">
      <div class="drop">把 docx 拖进来，或点击选择</div>
    </el-upload>

    <div class="ops">
      <el-button type="primary" :disabled="!file || uploading" :loading="uploading" @click="start">
        开始导入
      </el-button>
    </div>

    <div v-if="taskId" class="prog">
      <div class="line">{{ statusText[status] ?? '未知状态' }}
        <span v-if="total" class="soft">（成功 {{ ok }} / 失败 {{ fail }} / 共 {{ total }}）</span>
      </div>
      <el-progress :percentage="total ? Math.round(((ok + fail) / total) * 100) : 30"
        :status="status === 2 ? 'success' : status === 3 ? 'exception' : undefined"
        :indeterminate="status <= 1" :stroke-width="10" />
      <el-alert v-if="status === 3" type="error" :closable="false" :title="errorMsg || '导入失败'" />
      <el-button v-if="status === 2" type="success" plain @click="router.push('/question/list')">
        去题库看看
      </el-button>
    </div>
  </el-card>
</template>

<style scoped>
.wrap { border-radius: 14px; border: 1px solid var(--line); max-width: 760px }
.soft { color: var(--ink-soft); font-size: 12px }
.drop { padding: 26px 0; color: var(--ink-soft) }
.ops { margin: 16px 0 }
.prog { display: flex; flex-direction: column; gap: 10px; margin-top: 10px }
.line { font-weight: 600; color: var(--ink) }
</style>
```

**路由追加**（`router/index.ts` 的 children 里）：

```ts
{ path: 'import', component: () => import('../views/ImportView.vue') },
```

**侧栏入口**（HomeView 里 `add-cat` 按钮下方加一个）：

```vue
<el-button class="add-cat" size="small" text type="warning" @click="router.push('/import')">导入 docx</el-button>
```

## 🧪 测试与验收

| # | 用例 | 期望 |
|---|---|---|
| T1 | 上传《测开八股整理》| 秒回 taskId（<1s）；t_import_task 新增 status=0→1 |
| T2 | SSE 进度 | 每 0.5s 收到 progress 事件；终态 status=2 自动断开 |
| T3 | 入库抽查 | 随机 10 题：标题无编号残留、分类归属正确、图片 URL 可打开、表格成 Markdown、代码块有 ```java |
| T4 | 《手撕思路》导入 | 每题答案含代码围栏（单格表格识别成功）|
| T5 | 幂等（同文件再传）| 返回**同一个 taskId**，t_question 不新增 |
| T6 | 幂等（手动重投 MQ）| 日志出现"任务非排队态，跳过"，无重复题目 |
| T7 | 非 docx / 超 20MB | 400 明确文案 |
| T8 | 停掉 broker 后上传 | 接口仍返回 taskId，任务最终 status=3 且 errorMsg 有原因 |
| T9 | 越权 | B 账号查 A 的 taskId → 404 |
| T10 | 越权隔离（导入后）| B 的题库看不到 A 导入的题（租户插件兜底） |

**验收清单**：
- [ ] 10 条用例全绿
- [ ] 两份真实 docx 完整入库，抽查格式无损
- [ ] IDEA 控制台可见"导入任务消费者启动"与"导入完成"日志
- [ ] `docker start` 指南：broker 未启动时后端能正常起（**消费者启动失败不能拖垮应用**——若报错，给 Consumer 的 `afterPropertiesSet` 加 try/catch 降级并记日志）
- [ ] commit：`feat(phase4): docx导入管线(MQ+SSE)`

> **债务登记**：
> 1. `stored_path` 列（本章作业已布置，替代 errorMsg 复用）
> 2. SSE token 走 URL → 日志泄露风险；第 8 章部署改 cookie/短时签名
> 3. 逐题 `questionService.create()` = 每题一个事务；大批量可改批量插入（第 7 章压测时量化）
> 4. `q_type` 不自动识别"手撕"（靠分类名后半自动，暂人工改）
> 5. 图片文件孤儿（任务失败已落盘的图不会清理）→ 第 9 章部署前补清理任务

## 📦 本章交付物

后端：`importjob/` 域（entity/mapper/service/impl/consumer/parser/controller）+ `RocketMQConfig` + `SseConfig` + CategoryService.getOrCreate + LoginInterceptor token 兜底 + properties 两项。
前端：`api/import.ts` + `ImportView.vue` + 路由 + 侧栏入口。
依赖：`poi-ooxml`、`rocketmq-client`（我装）。

## 🔗 关联八股

- **MQ 三问**：生产端可靠性（同步 send 等 broker ack，失败置失败态）、消费幂等（CAS + 指纹）、重试与死信（RECONSUME_LATER 的代价）
- **ThreadLocal 两处必修**：消费者线程、SSE 线程——池化线程复用必须 set/clear，否则串号（第 7 章压测会现场复现串号事故）
- SSE vs 轮询 vs WebSocket：单向、文本、自动重连；EventSource 不能自定义 Header → token 走 query 的取舍
- POI/XWPF：`IBodyElement` 保序；图片从 run 的 `getEmbeddedPictures()` 取；`suggestFileExtension()` 拿扩展名
- SHA-256 指纹做幂等键（同内容=同身份）；对比"按文件名去重"的漏洞
- 状态机设计：四态 + CAS 流转（0→1→2/3），为什么终态不可逆

## 下章预告

**06 · FSRS 复习系统（Phase 5）**：把题库变成"会自己安排复习计划"的系统——FSRS 算法接入、卡片状态机、签到 BitMap、打卡榜 ZSet。届时你会第一次真切体会"每天打开就能刷"的产品闭环。
