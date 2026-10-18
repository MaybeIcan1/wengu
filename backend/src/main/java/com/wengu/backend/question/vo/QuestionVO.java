// question/vo/QuestionVO.java（列表——注意没有 answerMd）
package com.wengu.backend.question.vo;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
@Data
public class QuestionVO {
    private Long id;
    private String title;
    private Long categoryId;
    private String categoryName;
    private List<String> tagNames;
    private Integer qType;
    private String source;
    private Integer difficulty;
    private LocalDateTime updatedTime;
}