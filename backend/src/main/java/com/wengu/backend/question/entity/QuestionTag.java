// question/entity/QuestionTag.java（桥表PO，两张表都必须手动，无逻辑删/无填充）
package com.wengu.backend.question.entity;
import lombok.Data;

@Data
public class QuestionTag {
    private Long questionId;
    private Long tagId;
}