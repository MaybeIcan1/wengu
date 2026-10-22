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