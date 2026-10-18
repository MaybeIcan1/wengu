// question/entity/Question.java
package com.wengu.backend.question.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data
@TableName("t_question")
public class Question {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long categoryId;
    private String title;
    private String answerMd;
    private Integer qType;        // 0八股 1手撕 2主观 3笔试
    private String source;
    private Integer difficulty;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}