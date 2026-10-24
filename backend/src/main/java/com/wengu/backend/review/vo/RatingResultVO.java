package com.wengu.backend.review.vo;

public record RatingResultVO(String cardId, Integer state, String due,
                             Double stability, Double difficulty) {}
