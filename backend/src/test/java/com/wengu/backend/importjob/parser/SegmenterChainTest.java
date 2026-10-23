package com.wengu.backend.importjob.parser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 解析链路测试：BlockExtractor → RuleSegmenter(detectMode) → QuestionAssembler
 * 用手撕思路.docx 验证：自动判定为 QUESTION、5 道题、代码块随题归位。
 */
class SegmenterChainTest {

    @Test
    void 手撕文档_应自动判定为标题即题目() throws Exception {
        List<Block> blocks;
        try (InputStream in = getClass().getResourceAsStream("/importjob/手撕思路.docx")) {
            assertNotNull(in);
            BlockExtractor extractor = new BlockExtractor();
            // 该文档不含图片；mediaDir 不会被用到，留空即可
            var f = BlockExtractor.class.getDeclaredField("mediaDir");
            f.setAccessible(true);
            f.set(extractor, System.getProperty("java.io.tmpdir"));
            blocks = extractor.extract(in);
        }

        RuleSegmenter segmenter = new RuleSegmenter();
        Segmenter.HeadingMode mode = segmenter.detectMode(blocks);
        System.out.println("自动判定 mode = " + mode);

        List<Segment> segments = segmenter.segment(blocks, mode);
        QuestionAssembler assembler = new QuestionAssembler();
        List<ParsedQuestion> questions = assembler.assemble(blocks, segments);

        questions.forEach(q -> System.out.printf("[%s] %s (%d字)%n",
                q.getRule(), q.getTitle(),
                q.getAnswer() == null ? 0 : q.getAnswer().length()));

        assertEquals(Segmenter.HeadingMode.QUESTION, mode, "手撕文档应判定为标题即题目");
        assertEquals(5, questions.size(), "应有 5 道题（5 个 Heading）");
        assertTrue(questions.stream().allMatch(q -> "STRONG".equals(q.getRule())));

        Set<String> titles = questions.stream().map(ParsedQuestion::getTitle).collect(Collectors.toSet());
        assertTrue(titles.contains("无重复字符最长子串"));
        assertTrue(titles.contains("实现时间为nlogn的数组排序"));

        long withCode = questions.stream()
                .filter(q -> q.getAnswer().toString().contains("```java"))
                .count();
        // 该文档中两处无代码表格：①「两个递增数组的中位数」原文就是空章节（连续 Heading）
        //                         ②「一个数组长度为n…」的代码以截图形式存在（IMAGE 块）
        assertTrue(withCode >= 3, "至少 3 题应包含代码块，实际=" + withCode);

        long empty = questions.stream()
                .filter(q -> q.getAnswer().toString().contains("（空）"))
                .count();
        assertTrue(empty >= 1, "连续 Heading 产生的空题应保留并标记（预览页可删除），实际空题数=" + empty);
    }
}
