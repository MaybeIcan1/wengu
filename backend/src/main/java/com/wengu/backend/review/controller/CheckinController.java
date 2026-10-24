package com.wengu.backend.review.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.review.service.CheckinService;
import com.wengu.backend.review.vo.CheckinStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/checkin")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;

    @PostMapping
    public Result<CheckinStatusVO> checkin() { return Result.success(checkinService.checkin()); }

    @GetMapping("/status")
    public Result<CheckinStatusVO> status() { return Result.success(checkinService.status()); }
}
