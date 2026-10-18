package com.wengu.backend.question.vo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
public class QuestionDetailVO {
    private Long id;
    private String title;
    private String answerMd;
    private Long categoryId;
    private String categoryName;
    private List<String> tagNames;
    private Integer qType;
    private String source;
    private Integer difficulty;
    private LocalDateTime updatedTime;
}