// question/dto/TagDTO.java（字段照 CategoryDTO 抄，Size(max=30)）
package com.wengu.backend.question.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
@Data
public class TagDTO {
    @NotBlank(message = "标签名不能为空")
    @Size(max = 30, message = "标签名最长30字")
    private String name;
}