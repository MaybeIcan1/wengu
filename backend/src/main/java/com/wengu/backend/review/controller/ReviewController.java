package com.wengu.backend.review.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.review.service.ReviewService;
import com.wengu.backend.review.vo.RatingResultVO;
import com.wengu.backend.review.vo.ReviewSummaryVO;
import com.wengu.backend.review.vo.TodayCardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /** 开始复习会话：懒建卡 + 到期优先 + 随机补足 */
    @PostMapping("/session/start")
    public Result<List<TodayCardVO>> start(@RequestParam(required = false) Long categoryId,
                                           @RequestParam(defaultValue = "10") int count) {
        return Result.success(reviewService.startSession(categoryId, count));
    }

    @GetMapping("/summary")
    public Result<ReviewSummaryVO> summary() {
        return Result.success(reviewService.summary());
    }

    @PostMapping("/{cardId}/rating")
    public Result<RatingResultVO> rate(@PathVariable Long cardId, @RequestParam int rating) {
        return Result.success(reviewService.rate(cardId, rating));
    }

    @GetMapping("/random")
    public Result<TodayCardVO> random() {
        return Result.success(reviewService.random());
    }
}
