package com.wengu.backend.question.event;

/** 题目删除事件：复习域监听后清理对应卡片（避免 Service 循环依赖） */
public record QuestionDeletedEvent(Long questionId) {}
