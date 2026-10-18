// question/dto/QuestionUpdateDTO.java（同包，完整体；和Create的区别=多了主键id、少了"来源默认"）
package com.wengu.backend.question.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;
@Data
public class QuestionUpdateDTO {
    @NotNull(message = "id不能为空")
    private Long id;
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长100字")
    private String title;
    @NotBlank(message = "答案不能为空")
    private String answerMd;
    private Long categoryId;
    private List<String> tagNames;
    private Integer qType;
    private String source;
    private Integer difficulty;
}