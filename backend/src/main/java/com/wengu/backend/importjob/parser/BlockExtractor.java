// importjob/parser/BlockExtractor.java —— POI → Block 列表（确定性，无业务决策）
package com.wengu.backend.importjob.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class BlockExtractor {

    @Value("${wengu.media-dir}")
    private String mediaDir;

    public List<Block> extract(InputStream in) throws IOException {
        List<Block> blocks = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String text = p.getText() == null ? "" : p.getText().trim();
                    if (!text.isEmpty()) {
                        blocks.add(new Block(blocks.size(),
                                isHeading(p, text, doc.getStyles()) ? Block.HEADING : Block.PARA, text));
                    }
                    for (String url : extractImages(p)) {       // 图片独立成块（便于后续判归属）
                        blocks.add(new Block(blocks.size(), Block.IMAGE,
                                "![](" + url + ")\n\n"));
                    }
                } else if (el instanceof XWPFTable t) {
                    if (isSingleCell(t)) {
                        blocks.add(new Block(blocks.size(), Block.CODE,
                                "```java\n" + codeOf(t.getRow(0).getCell(0)) + "\n```\n"));
                    } else {
                        blocks.add(new Block(blocks.size(), Block.TABLE, toMarkdownTable(t)));
                    }
                }
            }
        }
        return blocks;
    }

    /**
     * 💣 实测坑（2026-10-22）：p.getStyle() 返回的是【样式ID】而不是【样式名】——
     * 中文版 Word 的 Heading 1 样式 ID 往往是 "2"，样式名才是 "heading 1"（或"标题 1"）。
     * 所以必须拿 styleId 去 styles.xml 查样式名再判，直接 contain("heading") 永远不命中。
     */
    private boolean isHeading(XWPFParagraph p, String text, XWPFStyles styles) {
        String styleId = p.getStyle();
        if (styleId != null) {
            // ① 按样式名判（最可靠）：heading 1 / 标题 1 / Heading 2 ...
            if (styles != null) {
                XWPFStyle style = styles.getStyle(styleId);
                if (style != null && style.getName() != null) {
                    String name = style.getName().toLowerCase();
                    if (name.contains("heading") || style.getName().contains("标题")) return true;
                }
            }
            // ② 兜底：样式名缺失时才按 ID 猜（Heading1 / 单个数字 1~9）
            String id = styleId.toLowerCase();
            if (id.startsWith("heading") || id.matches("^[1-9]$")) return true;
        }
        // ③ Markdown 风格文档：正文里直接以 # 开头
        return text.startsWith("#");
    }

    private boolean isSingleCell(XWPFTable t) {
        return t.getRows().size() == 1 && t.getRow(0).getTableCells().size() == 1;
    }

    /**
     * 💣 实测坑（2026-10-23）：XWPFTableCell.getText() 会把单元格内所有段落【无分隔】拼成一个字符串，
     * 代码表的"一行一个段落"会被挤成一行（多个函数挤在一起）。
     * 正确姿势：逐段落取值，用 \n 连接；段落内的软换行 <w:br/> 也要转成 \n。
     */
    private String codeOf(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : cell.getParagraphs()) {
            sb.append(paragraphText(p)).append('\n');
        }
        // 去掉末尾多余空行
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** 段落文本（保留软换行）：run.text() 拿到文字，<w:br/> 数量补 \n */
    private String paragraphText(XWPFParagraph p) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun r : p.getRuns()) {
            String t = r.text();
            if (t != null) sb.append(t);
            int brCount = r.getCTR().sizeOfBrArray();
            for (int i = 0; i < brCount; i++) sb.append('\n');
        }
        return sb.toString();
    }

    private String toMarkdownTable(XWPFTable t) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = t.getRows();
        for (int i = 0; i < rows.size(); i++) {
            List<XWPFTableCell> cells = rows.get(i).getTableCells();
            sb.append("|");
            for (XWPFTableCell c : cells) {
                sb.append(" ").append(c.getText().replace("\n", " ").trim()).append(" |");
            }
            sb.append("\n");
            if (i == 0) {
                sb.append("|");
                for (int j = 0; j < cells.size(); j++) sb.append(" --- |");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private List<String> extractImages(XWPFParagraph p) {
        List<String> urls = new ArrayList<>();
        for (XWPFRun run : p.getRuns()) {
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                try {
                    XWPFPictureData data = pic.getPictureData();
                    String ext = data.suggestFileExtension();
                    String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
                    Path dir = Paths.get(mediaDir, "import", month);
                    Files.createDirectories(dir);
                    String name = UUID.randomUUID() + "." + ext;
                    Files.write(dir.resolve(name), data.getData());
                    urls.add("/media/import/" + month + "/" + name);
                } catch (IOException e) {
                    log.warn("图片提取失败，跳过", e);
                }
            }
        }
        return urls;
    }
}
