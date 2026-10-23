// importjob/parser/Segmenter.java —— 决策层抽象（未来 LLM/本地分类器都实现它）
package com.wengu.backend.importjob.parser;

import java.util.List;

public interface Segmenter {

    /** 标题语义：同一份 docx，Heading 可能是一级分类，也可能就是题目本身 */
    enum HeadingMode {
        /** 标题=分类（如《测开八股整理》：语言相关八股/计算机网络/操作系统...） */
        CATEGORY,
        /** 标题=题目（如《手撕思路》：每个算法名就是一个 Heading） */
        QUESTION
    }

    /**
     * 只做"边界决策"：输入块序列，输出分段（索引区间），绝不修改任何文本内容。
     * 实现约定：Segment 必须按 titleIndex 升序、区间不重叠、索引在 [0, blocks.size()-1]。
     */
    List<Segment> segment(List<Block> blocks, HeadingMode mode);

    /**
     * 自动推断标题语义（用户不该被问这种问题）。
     * 默认实现保守返回 CATEGORY；规则版按"每标题下强规则题数 + 内容厚度"启发式判断。
     */
    default HeadingMode detectMode(List<Block> blocks) {
        return HeadingMode.CATEGORY;
    }
}