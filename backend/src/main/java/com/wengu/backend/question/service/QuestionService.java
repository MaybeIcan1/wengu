// question/service/QuestionService.java
package com.wengu.backend.question.service;
import com.baomidou.mybatisplus.spring.service.IService;
import com.wengu.backend.common.result.PageResult;
import com.wengu.backend.question.dto.*;
import com.wengu.backend.question.entity.Question;
import com.wengu.backend.question.query.QuestionPageQuery;
import com.wengu.backend.question.vo.*;

import java.util.List;

public interface QuestionService extends IService<Question> {
    Long create(QuestionCreateDTO dto);
    void update(QuestionUpdateDTO dto);
    PageResult<QuestionVO> page(QuestionPageQuery q);
    QuestionDetailVO detail(Long id);
    void delete(Long id);

    /** 复习会话输入：按分类取题（categoryId 为空 = 全部） */
    List<Question> listByCategory(Long categoryId);

    /** 随机问答：随机一道题 */
    Question randomOne();
}