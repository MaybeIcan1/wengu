package com.wengu.backend.importjob.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BlockExtractorFullDocTest {

    /** 改成你自己的测试文档，放在 src/test/resources 下 */
    private static final String DOC = "/importjob/手撕思路.docx";

    @TempDir
    Path tempMediaDir;

    private BlockExtractor extractor;

    @BeforeEach
    void setUp() throws Exception {
        extractor = new BlockExtractor();
        // 反射注入 @Value，避免启动 Spring
        Field f = BlockExtractor.class.getDeclaredField("mediaDir");
        f.setAccessible(true);
        f.set(extractor, tempMediaDir.toString());
    }

    @Test
    void 完整文档解析_含图片() throws Exception {
        List<Block> blocks;
        try (InputStream in = getClass().getResourceAsStream(DOC)) {
            assertNotNull(in, "测试文档不存在: " + DOC);
            blocks = extractor.extract(in);
        }

        assertFalse(blocks.isEmpty(), "解析结果不应为空");

        // 1) 打印概览，先肉眼看解析出了什么
        blocks.forEach(b -> System.out.printf("[%d] kind=%s text=%s%n",
                b.getIndex(), b.getKind(), brief(b.getText())));

        // 2) index 必须连续
        for (int i = 0; i < blocks.size(); i++) {
            assertEquals(i, blocks.get(i).getIndex(), "index 应连续递增");
        }

        // 3) 类型统计，方便发现某类块缺失
        Map<String, Long> byKind = blocks.stream()
                .collect(Collectors.groupingBy(Block::getKind, Collectors.counting()));
        System.out.println("块类型统计: " + byKind);

        // 4) 图片块必须落盘且 URL 正确
        List<Block> images = blocks.stream()
                .filter(b -> Block.IMAGE.equals(b.getKind()))
                .toList();
        assertFalse(images.isEmpty(), "文档里应有图片，图片块不应为空");
        for (Block img : images) {
            String url = img.getText()
                    .replace("![", "")
                    .replace("](", "")
                    .replace(")\n\n", "")
                    .trim();
            assertTrue(url.startsWith("/media/import/"), "图片 URL 前缀不对: " + url);
            Path written = tempMediaDir.resolve(url.replaceFirst("^/media/", ""));
            assertTrue(Files.exists(written), "图片未落盘: " + written);
            assertTrue(Files.size(written) > 0, "图片文件为空: " + written);
        }

        // 5) 文本块不应有首尾空白
        blocks.stream()
                .filter(b -> Block.PARA.equals(b.getKind()) || Block.HEADING.equals(b.getKind()))
                .forEach(b -> assertEquals(b.getText().trim(), b.getText(), "文本块不应有首尾空白"));
    }

    private String brief(String s) {
        if (s == null) return "null";
        String one = s.replace("\n", "\\n");
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }
}