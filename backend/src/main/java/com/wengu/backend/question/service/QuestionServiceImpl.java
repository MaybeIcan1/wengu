package com.wengu.backend.question.service;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.common.result.PageResult;
import com.wengu.backend.question.dto.*;
import com.wengu.backend.question.entity.*;
import com.wengu.backend.question.mapper.QuestionMapper;
import com.wengu.backend.question.mapper.QuestionTagMapper;
import com.wengu.backend.question.query.QuestionPageQuery;
import com.wengu.backend.question.event.QuestionDeletedEvent;
import com.wengu.backend.question.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
@Service
@RequiredArgsConstructor
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements QuestionService {
    private final TagService tagService;
    private final CategoryService categoryService;
    private final QuestionTagMapper questionTagMapper;
    private final ApplicationEventPublisher events;
    @Override
    @Transactional(rollbackFor = Exception.class)     // ★ 建题=三处写库，必须同生共死
    public Long create(QuestionCreateDTO dto) {
        Question q = new Question();
        q.setTitle(dto.getTitle());
        q.setAnswerMd(dto.getAnswerMd());
        q.setCategoryId(dto.getCategoryId());
        q.setQType(dto.getQType() == null ? 0 : dto.getQType());
        q.setSource(dto.getSource());
        q.setDifficulty(dto.getDifficulty() == null ? 0 : dto.getDifficulty());
        save(q);
        if (dto.getTagNames() != null && !dto.getTagNames().isEmpty()) {
            for (String name : dto.getTagNames()) {
                Tag t = tagService.createIfAbsent(name);   // 同时负责新建标签
                questionTagMapper.link(q.getId(), t.getId());
            }
        }
        return q.getId();
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(QuestionUpdateDTO dto) {
        Question q = getById(dto.getId());
        if (q == null) throw new BizException(404, "题目不存在");
        q.setTitle(dto.getTitle());
        q.setAnswerMd(dto.getAnswerMd());
        q.setCategoryId(dto.getCategoryId());
        q.setQType(dto.getQType());
        q.setSource(dto.getSource());
        q.setDifficulty(dto.getDifficulty());
        updateById(q);
        questionTagMapper.unlinkAll(dto.getId());      // 简单幂等：先清再插（对比：diff旧标签更省写但复杂，v1不选）
        if (dto.getTagNames() != null) {
            for (String name : dto.getTagNames()) {
                Tag t = tagService.createIfAbsent(name);
                questionTagMapper.link(dto.getId(), t.getId());
            }
        }
    }
    @Override
    public PageResult<QuestionVO> page(QuestionPageQuery query) {
        // 两段法第1段：标签→题id集合
        List<Long> matchedIds = null;
        if (query.getTagIds() != null && !query.getTagIds().isEmpty()) {
            matchedIds = query.getTagIds().stream()
                    .flatMap(tid -> questionTagMapper.questionIdsOf(tid).stream())
                    .distinct().collect(Collectors.toList());
            if (matchedIds.isEmpty()) return new PageResult<>(List.of(), 0);
        }
        // 两段法第2段：主表动态条件分页（.in 挂上时才拼条件）
        IPage<Question> p = lambdaQuery()
                .like(StringUtils.hasText(query.getKeyword()), Question::getTitle, query.getKeyword())
                .eq(query.getCategoryId() != null, Question::getCategoryId, query.getCategoryId())
                .eq(query.getQType() != null, Question::getQType, query.getQType())
                .in(matchedIds != null, Question::getId, matchedIds)
                .orderByDesc(Question::getUpdatedTime)
                .page(new Page<>(query.getPageNum(), query.getPageSize()));
        // 组装VO（批量化，防N+1）
        List<Question> rows = p.getRecords();
        if (rows.isEmpty()) return new PageResult<>(List.of(), 0);
        Map<Long, String> categoryNames = rows.stream().map(Question::getCategoryId)
                .filter(Objects::nonNull).distinct().toList().isEmpty()
                ? Map.of()
                : categoryService.listByIds(rows.stream().map(Question::getCategoryId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        // 一次捞整页题的关联
        List<QuestionTag> links = questionTagMapper.linksOf(
                rows.stream().map(Question::getId).toList());
        Map<Long, List<Long>> tidMap = links.stream().collect(Collectors.groupingBy(
                QuestionTag::getQuestionId, Collectors.mapping(QuestionTag::getTagId, Collectors.toList())));
        List<Tag> tags = tidMap.isEmpty() ? List.of()
                : tagService.listByIds(tidMap.values().stream().flatMap(List::stream).distinct().toList());
        Map<Long, String> tagNames = tags.stream().collect(Collectors.toMap(Tag::getId, Tag::getName));
        List<QuestionVO> vos = rows.stream().map(o -> {
            QuestionVO v = new QuestionVO();
            v.setId(o.getId()); v.setTitle(o.getTitle()); v.setCategoryId(o.getCategoryId());
            // 💣 Map.of() 不可变Map连 get(null) 都会抛 NPE（数组不用键值查null）——先判空再查
            v.setCategoryName(o.getCategoryId() == null ? null : categoryNames.get(o.getCategoryId()));
            v.setQType(o.getQType()); v.setSource(o.getSource()); v.setDifficulty(o.getDifficulty());
            v.setUpdatedTime(o.getUpdatedTime());
            List<Long> tids = tidMap.getOrDefault(o.getId(), List.of());
            v.setTagNames(tids.stream().map(tn -> tagNames.get(tn)).filter(Objects::nonNull).toList());
            return v;
        }).toList();
        return new PageResult<>(vos, p.getTotal());
    }
    @Override
    public QuestionDetailVO detail(Long id) {
        Question q = getById(id);                       // 租户插件保证：他人数据=查无
        if (q == null) throw new BizException(404, "题目不存在");
        QuestionDetailVO v = new QuestionDetailVO();
        v.setId(q.getId()); v.setTitle(q.getTitle()); v.setAnswerMd(q.getAnswerMd());
        v.setCategoryId(q.getCategoryId()); v.setQType(q.getQType());
        v.setSource(q.getSource()); v.setDifficulty(q.getDifficulty()); v.setUpdatedTime(q.getUpdatedTime());
        if (q.getCategoryId() != null) {
            Category c = categoryService.getById(q.getCategoryId());
            v.setCategoryName(c == null ? null : c.getName());
        }
        List<Long> tids = questionTagMapper.tagIdsOf(q.getId());
        if (!tids.isEmpty()) {
            v.setTagNames(tagService.listByIds(tids).stream().map(Tag::getName).toList());
        }
        return v;
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Question q = getById(id);
        if (q == null) throw new BizException(404, "题目不存在");
        removeById(id);                                  // 逻辑删除（deleted=1）
        questionTagMapper.unlinkAll(id);                 // ★ 桥表无deleted，物理清
        events.publishEvent(new QuestionDeletedEvent(id));   // 复习域监听：清理卡片
    }

    @Override
    public List<Question> listByCategory(Long categoryId) {
        return lambdaQuery()
                .eq(categoryId != null, Question::getCategoryId, categoryId)
                .orderByDesc(Question::getUpdatedTime)
                .list();
    }

    @Override
    public Question randomOne() {
        long total = lambdaQuery().count();
        if (total == 0) return null;
        long offset = ThreadLocalRandom.current().nextLong(total);
        return lambdaQuery().last("limit 1 offset " + offset).one();
    }
}