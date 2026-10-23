// importjob/parser/RuleSegmenter.java
package com.wengu.backend.importjob.parser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "wengu.import.segmenter", havingValue = "rule", matchIfMissing = true)
public class RuleSegmenter implements Segmenter {

    @Override
    public List<Segment> segment(List<Block> blocks, Segmenter.HeadingMode mode) {
        List<Segment> out = new ArrayList<>();
        String category = null;
        String currentRule = null;
        int currentStart = -1;      // 当前题的标题块
        int scatterStart = -1;      // 当前散记区起点（与 currentStart 互斥）

        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);

            if (Block.HEADING.equals(b.getKind())) {
                // ★ 同一结构两种语义：先收尾，再由上传时声明的模式决定它是"分类"还是"题目"
                flush(out, currentStart, i - 1, category, currentRule);
                flush(out, scatterStart, i - 1, category, "SCATTER");
                currentStart = -1; scatterStart = -1; currentRule = null;
                if (mode == Segmenter.HeadingMode.QUESTION) {
                    currentStart = i;              // 标题即题目：直接开新题
                    currentRule = "STRONG";        // 样式级强信号，与 ①/Q： 同级
                } else {
                    category = b.getText().replaceAll("^#+\\s*", "").trim();
                }
                continue;
            }

            boolean strong = Block.PARA.equals(b.getKind())
                    && Patterns.Q_START.matcher(b.getText()).find();
            // 弱规则：分类内、当前题已有答案、行短且无句读/逗号、非列表符号开头
            boolean weak = Block.PARA.equals(b.getKind()) && currentStart >= 0
                    && i - 1 > currentStart                            // 当前题已有内容
                    && isWeakTitle(b.getText());

            if (strong || weak) {
                flush(out, currentStart, i - 1, category, currentRule);
                flush(out, scatterStart, i - 1, category, "SCATTER");
                currentStart = i;
                currentRule = strong ? "STRONG" : "WEAK";
                scatterStart = -1;
            } else if (currentStart < 0 && scatterStart < 0) {
                scatterStart = i;                                      // 无归属内容 → 散记区
            }
            // 其余情况：内容自然落进当前题或当前散记区
        }
        flush(out, currentStart, blocks.size() - 1, category, currentRule);
        flush(out, scatterStart, blocks.size() - 1, category, "SCATTER");
        return out;
    }

    private void flush(List<Segment> out, int start, int end, String category, String rule) {
        if (start < 0 || end < start || rule == null) return;
        out.add(new Segment(start, end, category, rule));
    }

    /**
     * ★ 自动推断标题语义（两段式设计的第一段）：
     * 分类型文档：每个分类下有多道 ①/Q： 题 → strongPerHeading 大
     * 题目型文档：一个标题下只有"思路+代码" → 几乎无强规则题，且内容薄
     */
    @Override
    public HeadingMode detectMode(List<Block> blocks) {
        int headings = 0, contentBlocks = 0, strongTitles = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if (!Block.HEADING.equals(blocks.get(i).getKind())) continue;
            headings++;
            int end = i + 1;
            while (end < blocks.size() && !Block.HEADING.equals(blocks.get(end).getKind())) end++;
            contentBlocks += (end - i - 1);
            for (int j = i + 1; j < end; j++) {
                Block b = blocks.get(j);
                if (Block.PARA.equals(b.getKind())
                        && Patterns.Q_START.matcher(b.getText()).find()) strongTitles++;
            }
            i = end - 1;
        }
        if (headings == 0) return HeadingMode.CATEGORY;       // 无标题：按分类结构兜底
        double strongPerHeading = (double) strongTitles / headings;
        double blocksPerHeading = (double) contentBlocks / headings;
        if (strongPerHeading >= 1.2) return HeadingMode.CATEGORY;   // 每类下有多题 → 分类
        return blocksPerHeading < 6 ? HeadingMode.QUESTION : HeadingMode.CATEGORY;
    }

    private boolean isWeakTitle(String text) {
        if (text.isEmpty() || text.length() > 40) return false;
        if (text.contains("，") || text.contains("、") || text.contains(",")) return false;
        if (Patterns.BAD_END.indexOf(text.charAt(text.length() - 1)) >= 0) return false;
        for (String b : Patterns.BULLETS) if (text.startsWith(b)) return false;
        return true;
    }
}