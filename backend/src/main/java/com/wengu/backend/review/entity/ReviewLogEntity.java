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
