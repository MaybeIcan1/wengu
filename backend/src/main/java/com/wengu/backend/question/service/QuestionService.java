// question/service/QuestionService.java
package com.wengu.backend.question.service;
import com.baomidou.mybatisplus.spring.service.IService;
import com.wengu.backend.common.result.PageResult;
import com.wengu.backend.question.dto.*;
import com.wengu.backend.question.entity.Question;
import com.wengu.backend.question.query.QuestionPageQuery;
import com.wengu.backend.question.vo.*;

public interface QuestionService extends IService<Question> {
    Long create(QuestionCreateDTO dto);
    void update(QuestionUpdateDTO dto);
    PageResult<QuestionVO> page(QuestionPageQuery q);
    QuestionDetailVO detail(Long id);
    void delete(Long id);
}