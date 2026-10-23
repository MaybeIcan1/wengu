package com.wengu.backend.importjob.parser;

import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

/** 临时诊断：打印段落样式元数据，定位 Heading 识别失败原因（用完即删） */
class StyleDumpTest {

    @Test
    void dump() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/importjob/手撕思路.docx");
             XWPFDocument doc = new XWPFDocument(in)) {
            XWPFStyles styles = doc.getStyles();
            int i = 0;
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String t = p.getText() == null ? "" : p.getText().trim();
                    if (t.isEmpty()) continue;
                    String styleId = p.getStyle();
                    String styleName = null;
                    if (styleId != null && styles != null) {
                        XWPFStyle st = styles.getStyle(styleId);
                        styleName = (st == null ? null : st.getName());
                    }
                    Integer outline = null;
                    try {
                        var ppr = p.getCTP().getPPr();
                        if (ppr != null && ppr.isSetOutlineLvl()) {
                            outline = ppr.getOutlineLvl().getVal().intValue();
                        }
                    } catch (Exception ignored) {
                    }
                    boolean bold = p.getRuns().stream().anyMatch(XWPFRun::isBold);
                    int size = p.getRuns().stream()
                            .mapToInt(r -> r.getFontSize() == -1 ? 0 : r.getFontSize())
                            .max().orElse(-1);
                    System.out.printf("P%02d styleId=[%s] styleName=[%s] outline=%s bold=%s size=%s text=%s%n",
                            i++, styleId, styleName, outline, bold, size,
                            t.substring(0, Math.min(34, t.length())));
                }
            }
        }
    }
}
