package com.wengu.backend.review.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_card")
public class ReviewCard {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long questionId;
    private Integer state;
    private Integer step;
    private Double stability;
    private Double difficulty;
    private LocalDateTime due;
    private LocalDateTime lastReview;
    private Integer reps;
    private Integer lapses;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}
