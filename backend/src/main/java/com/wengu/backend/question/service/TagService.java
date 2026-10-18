package com.wengu.backend.question.service;
import com.baomidou.mybatisplus.spring.service.IService;
import com.wengu.backend.question.dto.TagDTO;
import com.wengu.backend.question.entity.Tag;
import com.wengu.backend.question.vo.TagVO;
import java.util.List;

public interface TagService extends IService<Tag> {
    Tag createIfAbsent(String name);         // ★ 不存在就建，存在就还已有——录题时随手建标签的基石
    List<TagVO> listMine();
}