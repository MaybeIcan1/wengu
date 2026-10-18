package com.wengu.backend.question.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.question.dto.CategoryDTO;
import com.wengu.backend.question.dto.TagDTO;
import com.wengu.backend.question.entity.Tag;
import com.wengu.backend.question.service.CategoryService;
import com.wengu.backend.question.service.TagService;
import com.wengu.backend.question.vo.CategoryVO;
import com.wengu.backend.question.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tag")
@RequiredArgsConstructor
public class TagController {
    private final TagService tagService;

    @PostMapping
    public Result<TagVO> create(@Validated @RequestBody TagDTO dto) {
        Tag tag = tagService.createIfAbsent(dto.getName());
        TagVO v = new TagVO();
        v.setId(tag.getId());
        v.setName(tag.getName());
        return Result.success(v);
    }

    @GetMapping("/list")
    public Result<List<TagVO>> list() {
        return Result.success(tagService.listMine());
    }
}
