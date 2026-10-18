// question/controller/CategoryController.java
package com.wengu.backend.question.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.question.dto.CategoryDTO;
import com.wengu.backend.question.service.CategoryService;
import com.wengu.backend.question.vo.CategoryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping
    public Result<Long> create(@Validated @RequestBody CategoryDTO dto) {
        return Result.success(categoryService.create(dto));
    }

    @GetMapping("/list")
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.listWithCount());
    }
}