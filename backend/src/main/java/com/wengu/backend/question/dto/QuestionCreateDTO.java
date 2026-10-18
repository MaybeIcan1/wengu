// question/dto/QuestionCreateDTO.java
package com.wengu.backend.question.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;
@Data
public class QuestionCreateDTO {
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长100字")
    private String title;
    @NotBlank(message = "答案不能为空")
    private String answerMd;
    private Long categoryId;
    private List<String> tagNames;         // 前端传名字，后端负责"不存在就建"
    private Integer qType;                 // 0-3，默认0
    private String source;
    private Integer difficulty;            // 0-3，默认0
}