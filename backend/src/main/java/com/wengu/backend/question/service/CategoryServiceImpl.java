package com.wengu.backend.question.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.question.dto.CategoryDTO;
import com.wengu.backend.question.entity.Category;
import com.wengu.backend.question.entity.Question;
import com.wengu.backend.question.mapper.CategoryMapper;
import com.wengu.backend.question.mapper.QuestionMapper;
import com.wengu.backend.question.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService{
    private final QuestionMapper questionMapper;
    @Override
    public Long create(CategoryDTO dto){
        //同用户重名删掉
        Long count = lambdaQuery().eq(Category::getName,dto.getName()).count();
        if(count > 0) throw new BizException(400,"分类已存在");
        Category c = new Category();
        c.setName(dto.getName()); // sort=0默认
        save(c);
        return c.getId();
    }

    @Override
    public List<CategoryVO> listWithCount() {
        // 底下查分类，上面带出每类题目数——两条简单SQL组合，比JOIN+GROUP BY的复杂SQL好读也够用（规模小）
        List<CategoryVO> vos = lambdaQuery().orderByAsc(Category::getSort)
                .list().stream().map(c -> { CategoryVO v = new CategoryVO();
                    v.setId(c.getId()); v.setName(c.getName()); v.setSort(c.getSort());
                    return v; }).toList();
        // 每类计数
        List<Map<String, Object>> rows = questionMapper.selectMaps(
                Wrappers.<Question>query()
                        .select("category_id", "count(*) as cnt")
                        .isNotNull("category_id")          // 💣 无分类的题会分组出NULL行→get后longValue炸（v2骨架有此行，抄稿丢了）
                        .groupBy("category_id")
        );

        Map<Long, Long> counter = rows.stream()
                .filter(m -> m.get("category_id") != null)   // 双保险：NULL键直接跳过
                .collect(Collectors.toMap(
                        m -> ((Number) m.get("category_id")).longValue(),
                        m -> ((Number) m.get("cnt")).longValue()
                ));
        vos.forEach(v -> v.setQuestionCount(counter.getOrDefault(v.getId(), 0L)));
        return vos;
    }

    @Override
    public Long getOrCreate(String name){
        if (name == null || name.isBlank()) return null;
        Category c = lambdaQuery().eq(Category::getName, name).one();
        if (c != null) return c.getId();
        CategoryDTO dto = new CategoryDTO();
        dto.setName(name.length() > 50 ? name.substring(0, 50) : name);
        return create(dto);
    }
}
