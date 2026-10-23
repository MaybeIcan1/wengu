// importjob/service/ImportService.java
package com.wengu.backend.importjob.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.wengu.backend.importjob.entity.ImportDraft;
import com.wengu.backend.importjob.entity.ImportTask;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface ImportService extends IService<ImportTask> {

    Long upload(MultipartFile file, Long userId);       // 标题语义由解析时自动检测

    /** 预览页纠正标题语义：重新解析（仅 status=2 允许），返回新草稿数 */
    int reparse(Long taskId, Long userId, String headingMode);

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