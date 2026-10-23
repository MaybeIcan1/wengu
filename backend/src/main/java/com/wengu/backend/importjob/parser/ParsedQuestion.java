// importjob/parser/ParsedQuestion.java
package com.wengu.backend.importjob.parser;

import lombok.Data;

@Data
public class ParsedQuestion {
    public static final String STRONG = "STRONG";
    public static final String WEAK = "WEAK";
    public static final String SCATTER = "SCATTER";
    // 预留：LLM_HIGH（模型高置信边界）/ LLM_LOW（低置信，预览页重点标黄）

    private String title;
    private String category;
    private String source;
    private String rule;
    private StringBuilder answer = new StringBuilder();
}