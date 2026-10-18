// question/dto/CategoryDTO.java
package com.wengu.backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryDTO {
    @NotBlank(message = "分类名不能为空")
    @Size(max = 50)
    private String name;
}

