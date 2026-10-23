// importjob/parser/Patterns.java —— 正则集中管理（Segmenter 与 Assembler 共用）
package com.wengu.backend.importjob.parser;

import java.util.List;
import java.util.regex.Pattern;

public final class Patterns {
    /** 强规则：① / 1. / Q： */
    public static final Pattern Q_START =
            Pattern.compile("^(?:[①-⑳]|[0-9]{1,2}\\s*[.、．)）]|Q\\s*[:：])");
    public static final Pattern TITLE_PREFIX =
            Pattern.compile("^(?:[①-⑳]|[0-9]{1,2}\\s*[.、．)）]|Q\\s*[:：])\\s*");
    public static final Pattern SOURCE = Pattern.compile("【([^】]{1,50})】");
    /** 弱标题禁止的结尾标点（句子特征） */
    public static final String BAD_END = "：:。，,、；;！!？?”\"'）)";
    public static final List<String> BULLETS = List.of("-", "·", "•", "*", "+", ">");

    private Patterns() {}
}