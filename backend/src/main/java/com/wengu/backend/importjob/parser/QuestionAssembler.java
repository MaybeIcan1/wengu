// importjob/parser/QuestionAssembler.java —— 装配层：按索引拼原文，逐字不改
package com.wengu.backend.importjob.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

@Component
public class QuestionAssembler {

    public List<ParsedQuestion> assemble(List<Block> blocks, List<Segment> segments) {
        List<ParsedQuestion> out = new ArrayList<>();
        for (Segment s : segments) {
            ParsedQuestion q = new ParsedQuestion();
            q.setRule(s.getRule());
            q.setCategory(s.getCategory());

            if ("SCATTER".equals(s.getRule())) {
                q.setTitle(truncate("散记 · "
                        + (s.getCategory() == null ? "未分类" : s.getCategory()), 100));
            } else {
                String raw = blocks.get(s.getTitleIndex()).getText();
                Matcher m = Patterns.SOURCE.matcher(raw);
                if (m.find()) q.setSource(m.group(1));
                String t = Patterns.TITLE_PREFIX.matcher(raw).replaceFirst("").trim();
                q.setTitle(truncate(t.isEmpty() ? raw : t, 100));
            }

            StringBuilder ans = new StringBuilder();
            for (int i = s.getTitleIndex(); i <= s.getEndIndexInclusive() && i < blocks.size(); i++) {
                if (!"SCATTER".equals(s.getRule()) && i == s.getTitleIndex()) continue;  // 标题行不入答案
                String text = blocks.get(i).getText();
                ans.append(text);
                if (!text.endsWith("\n")) ans.append("\n\n");
            }
            if (ans.length() == 0) ans.append("（空）\n");
            q.setAnswer(ans);
            out.add(q);
        }
        return out;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}