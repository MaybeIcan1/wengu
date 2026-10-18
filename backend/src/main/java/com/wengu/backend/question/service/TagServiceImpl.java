package com.wengu.backend.question.service;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.wengu.backend.question.dto.TagDTO;
import com.wengu.backend.question.entity.Tag;
import com.wengu.backend.question.mapper.TagMapper;
import com.wengu.backend.question.vo.TagVO;
import com.wengu.backend.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;


@Service
@RequiredArgsConstructor
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements TagService {
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Tag createIfAbsent(String name) {
        Tag existing = lambdaQuery().eq(Tag::getName, name).one();
        if (existing != null) return existing;          // 快路径：读命中直接回
        Tag t = new Tag();
        t.setName(name);
        try {
            save(t);
            return t;
        } catch (DuplicateKeyException e) {              // ★ 撞 uk_user_name=并发窗口兜底，回查收场
            return lambdaQuery().eq(Tag::getName, name).one();
        }
    }
    @Override
    public List<TagVO> listMine() {
        return list().stream().map(t -> {
            TagVO v = new TagVO();
            v.setId(t.getId());
            v.setName(t.getName());
            return v;
        }).toList();
    }
}