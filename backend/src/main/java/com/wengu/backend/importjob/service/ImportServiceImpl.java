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
        task.setHeadingMode("CATEGORY");           // 占位，解析时用自动检测结果覆盖
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

            List<Block> blocks = extractBlocks(task);
            Segmenter.HeadingMode mode = segmenter.detectMode(blocks);   // ★ 自动推断，不问用户
            int count = parseAndStore(task, userId, blocks, mode);
            log.info("解析完成 taskId={} mode={} drafts={}", taskId, mode, count);
        } catch (Exception e) {
            log.error("解析失败 taskId={}", taskId, e);
            markFailed(taskId, userId, truncateRoot(e));
        } finally {
            UserContext.clear();                        // ★ 池化线程必清
        }
    }

    /** 预览页纠正标题语义：重新分段并重写草稿（仅待确认任务可操作） */
    @Override
    public int reparse(Long taskId, Long userId, String headingMode) {
        UserContext.set(userId);
        try {
            ImportTask task = getById(taskId);
            if (task == null) throw new BizException(404, "任务不存在");
            if (task.getStatus() == null || task.getStatus() != 2)
                throw new BizException(400, "只有待确认的任务可以重新解析");
            Segmenter.HeadingMode mode = "QUESTION".equalsIgnoreCase(headingMode)
                    ? Segmenter.HeadingMode.QUESTION : Segmenter.HeadingMode.CATEGORY;
            return parseAndStore(task, userId, extractBlocks(task), mode);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "重新解析失败：" + e.getClass().getSimpleName());
        } finally {
            UserContext.clear();
        }
    }

    private List<Block> extractBlocks(ImportTask task) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(
                Paths.get(importDir, task.getStoredPath()).toFile())) {
            return blockExtractor.extract(in);
        }
    }

    /** 解析→清旧草稿→写新草稿→任务待确认；parseToDrafts 与 reparse 共用 */
    private int parseAndStore(ImportTask task, Long userId, List<Block> blocks,
                              Segmenter.HeadingMode mode) {
        List<ParsedQuestion> parsed = questionAssembler.assemble(
                blocks, segmenter.segment(blocks, mode));      // ② 定边界 ③ 装配原文
        draftMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ImportDraft>lambdaQuery().eq(ImportDraft::getTaskId, task.getId()));
        int seq = 0;
        for (ParsedQuestion pq : parsed) {
            ImportDraft d = new ImportDraft();
            d.setTaskId(task.getId());
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
        lambdaUpdate().eq(ImportTask::getId, task.getId())
                .set(ImportTask::getStatus, 2)               // 待确认
                .set(ImportTask::getHeadingMode, mode.name()) // 记录生效的语义，预览页展示
                .set(ImportTask::getTotalCount, parsed.size())
                .update();
        return parsed.size();
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