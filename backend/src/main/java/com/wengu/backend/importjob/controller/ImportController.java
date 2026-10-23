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
        private String headingMode;      // 自动检测/用户纠正后的生效语义
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

    /** 预览页切换标题语义：CATEGORY | QUESTION，重新分段并刷新草稿 */
    @PostMapping("/task/{id}/reparse")
    public Result<Integer> reparse(@PathVariable Long id,
                                   @RequestParam(value = "mode", defaultValue = "CATEGORY") String mode) {
        return Result.success(importService.reparse(id, UserContext.requireUserId(), mode));
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
                t.getSuccessCount(), t.getFailCount(), t.getErrorMsg(), t.getFileName(),
                t.getHeadingMode());
    }
}