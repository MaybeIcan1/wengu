package com.wengu.backend.question.query;
import lombok.Data;
import java.util.List;
@Data
public class QuestionPageQuery {
    private Long pageNum = 1L;
    private Long pageSize = 10L;
    private String keyword;
    private Long categoryId;
    private List<Long> tagIds;       // 任一命中即入选（OR 语义）
    private Integer qType;
}