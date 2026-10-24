package com.wengu.backend.review.vo;

/** 复习卡片（会话下发）：cardId 为空表示随机问答（不参与评分） */
public record TodayCardVO(String cardId, String questionId, String title,
                          String answerMd, Integer state, String due) {}
