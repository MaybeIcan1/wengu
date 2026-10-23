// question/service/CategoryService.java
package com.wengu.backend.question.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.wengu.backend.question.dto.CategoryDTO;
import com.wengu.backend.question.entity.Category;
import com.wengu.backend.question.vo.CategoryVO;
import java.util.List;
public interface CategoryService extends IService<Category> {
    Long create(CategoryDTO dto);          // 返回新分类id，方便前端立刻选中它
    List<CategoryVO> listWithCount();
    Long getOrCreate(String name);
}