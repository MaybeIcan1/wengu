// question/controller/QuestionController.java
package com.wengu.backend.question.controller;

import com.wengu.backend.common.result.PageResult;
import com.wengu.backend.common.result.Result;
import com.wengu.backend.question.dto.*;
import com.wengu.backend.question.query.QuestionPageQuery;
import com.wengu.backend.question.service.QuestionService;
import com.wengu.backend.question.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/question")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @PostMapping
    public Result<Long> create(@Validated @RequestBody QuestionCreateDTO dto) {
        return Result.success(questionService.create(dto));
    }

    @PutMapping
    public Result<Void> update(@Validated @RequestBody QuestionUpdateDTO dto) {
        questionService.update(dto);
        return Result.success();
    }

    @GetMapping("/page")
    public Result<PageResult<QuestionVO>> page(QuestionPageQuery query) {
        return Result.success(questionService.page(query));
    }

    @GetMapping("/{id}")
    public Result<QuestionDetailVO> detail(@PathVariable Long id) {
        return Result.success(questionService.detail(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return Result.success();
    }
}