// importjob/parser/Segment.java —— 决策产物：只描述"索引区间 + 分类 + 依据"
package com.wengu.backend.importjob.parser;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Segment {
    private int titleIndex;          // 标题所在块
    private int endIndexInclusive;   // 该题最后一块的索引（含）
    private String category;         // 所属分类（来自最近的 HEADING）
    private String rule;             // STRONG / WEAK / SCATTER ；预留 LLM_HIGH / LLM_LOW
}