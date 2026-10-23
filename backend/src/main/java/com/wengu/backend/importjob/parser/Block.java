// importjob/parser/Block.java —— 文档的最小结构单元
package com.wengu.backend.importjob.parser;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Block {
    public static final String HEADING = "HEADING";
    public static final String PARA = "PARA";
    public static final String CODE = "CODE";
    public static final String TABLE = "TABLE";
    public static final String IMAGE = "IMAGE";

    private int index;      // 文档内序号（Segmenter 决策的依据）
    private String kind;    // 上面五个常量之一
    private String text;    // 段落原文 / 代码围栏 / markdown表格 / 图片markdown
}