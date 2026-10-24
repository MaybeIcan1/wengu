package com.wengu.backend.review.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.review.service.RankService;
import com.wengu.backend.review.vo.RankItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/rank")
@RequiredArgsConstructor
public class RankController {

    private final RankService rankService;

    @GetMapping("/today")
    public Result<List<RankItemVO>> today(@RequestParam(defaultValue = "10") int top) {
        return Result.success(rankService.todayTop(Math.min(Math.max(top, 1), 50)));
    }
}
