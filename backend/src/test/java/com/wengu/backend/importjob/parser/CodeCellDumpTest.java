package com.wengu.backend.importjob.parser;

import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

/** 临时诊断：代码单元格里的换行到底存在哪（多段落？软换行 w:br？） */
class CodeCellDumpTest {

    @Test
    void dumpFirstCodeCell() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/importjob/手撕思路.docx");
             XWPFDocument doc = new XWPFDocument(in)) {
            int cellSeen = 0;
            for (IBodyElement el : doc.getBodyElements()) {
                if (!(el instanceof XWPFTable t)) continue;
                if (!(t.getRows().size() == 1 && t.getRow(0).getTableCells().size() == 1)) continue;
                XWPFTableCell cell = t.getRow(0).getCell(0);
                cellSeen++;
                if (cellSeen > 1) break;

                System.out.println("=== 单元格段落数: " + cell.getParagraphs().size() + " ===");
                int i = 0;
                for (XWPFParagraph p : cell.getParagraphs()) {
                    StringBuilder runInfo = new StringBuilder();
                    for (XWPFRun r : p.getRuns()) {
                        runInfo.append("{br=").append(r.getCTR().sizeOfBrArray())
                               .append(",tabs=").append(r.getCTR().sizeOfTabArray())
                               .append(",text=").append(shorten(r.text()))
                               .append("}");
                    }
                    System.out.printf("P%02d cellText=[%s] runs=%s%n",
                            i++, shorten(p.getText()), runInfo);
                }
                System.out.println("=== cell.getText() 前 220 字 ===\n" + shorten(cell.getText()));
            }
        }
    }

    private String shorten(String s) {
        if (s == null) return "null";
        String one = s.replace("\n", "\\n").replace("\r", "\\r");
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
