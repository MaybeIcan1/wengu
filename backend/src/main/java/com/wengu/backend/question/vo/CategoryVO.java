package com.wengu.backend.question.vo;

import lombok.Data;
@Data
public class CategoryVO {
    private Long id;
    private String name;
    private Integer sort;
    private Long questionCount;     // 侧边栏"每类几题"用
}