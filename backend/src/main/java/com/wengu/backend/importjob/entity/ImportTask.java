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
    private String headingMode;    // CATEGORY / QUESTION（见 Segmenter.HeadingMode）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}