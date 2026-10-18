# 03 · 题目域 CRUD 与图片（Phase 2）

> 状态：✅ **AI 全量测试通过（2026-10-18，T1~T15 全绿）**；收官剩：浏览器主角故事 + commit `feat(phase2): 题目域CRUD与图片上传` ｜ 修订 v2（2026-10-17 按反馈重写）+ v2.1（用户实测补漏）
> **v2 变更承诺**：①每个模块先列"缺什么文件清单"（建在哪+各文件角色），②全部骨架→**完整可抄代码**，不存在"见章末/略/实看 git"三个词。③标记 ★ 的行仍要懂（验收抽查）。
> 本章目标一句话：**在浏览器里完整管一道"带图、带分类、带标签"的题**——录入、筛选列表、看详情、编辑、删除，全程多租户隔离。预计 2 次会话（第 1 次=4 张表+后端；第 2 次=前端+测试+验收）。

---

## 🎯 本章目标与「我需要什么」

**业务需求（讲人话版）**：你刷新面经，想把一道 Redis 的题归档：建"数据库"分类、贴 `Redis`/`高频` 标签；两天后按关键词/分类/标签把它捞出来；看详情时图能显示；答案写错了能改；没价值能删。

**需要**：Phase 1 全绿；Navicat、Apifox；数据库 4 张表先建好（↓）。

## 🗄️ 数据库：4 张表 + 1 张关联表

**设计思路三连答**：

1. **分类为什么单独建表？** 直接在题目行存"分类名"字符串也能跑，但改名要全表 UPDATE、统计每类题数要全表扫、排序无从谈起。独立建表 = 一处改名 + 一次 group 搞定。
2. **标签为什么多对多？** 一个标签贴 N 道题、一道题贴 N 个标签，教科书关系。备选方案"逗号字符串"（无关联表、字段里存 `Redis,高频`）为什么放弃：查"贴了 Redis 的所有题"只能 LIKE 扫全表；改名、计数、统计全废；还会脏。
3. **索引**：列表永远带 user_id（多租户），常按 category_id 筛 → 联合索引 `idx_user_category(user_id, category_id)`，最左前缀两场景全吃。title 模糊搜索不建普通索引（LIKE '%x' 用不上，升级路线第 7 章谈）。

```sql
-- 1. 分类
CREATE TABLE t_category (
  id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  name VARCHAR(50) NOT NULL, sort INT DEFAULT 0,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分类';

-- 2. 标签
CREATE TABLE t_tag (
  id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  name VARCHAR(30) NOT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_user_name (user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标签';

-- 3. 题目主表
CREATE TABLE t_question (
  id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  category_id BIGINT DEFAULT NULL,
  title VARCHAR(100) NOT NULL,
  answer_md MEDIUMTEXT NOT NULL COMMENT 'Markdown答案',
  q_type TINYINT DEFAULT 0 COMMENT '0八股 1手撕 2主观 3笔试',
  source VARCHAR(50) DEFAULT NULL COMMENT '来源：牛客/美的机考…',
  difficulty TINYINT DEFAULT 0 COMMENT '0未标 1易 2中 3难',
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_user_category (user_id, category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目';

-- 4. 题目-标签关联（纯多对多桥，无时间列/无逻辑删）
CREATE TABLE t_question_tag (
  question_id BIGINT NOT NULL, tag_id BIGINT NOT NULL,
  PRIMARY KEY (question_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目标签关联';
```

> ★ 执行后自问：MEDIUMTEXT 与 TEXT 的容量差？（16MB vs 64KB）为什么手撕代码选大一号的？

## ⚙️ 后端

### 第 0 步 · 包结构落位（为什么新开 question 包？）

**发生了什么**：项目里目前有 `common/ config/ security/`；本章开始要**新建业务包 `question`**，以后还有 review/import/quota/ai 会陆续出现——不解释你就是在猜。

| 分包姿势 | 长相 | 适用 |
|---|---|---|
| 按技术层平铺 | com.wengu.controller.QuestionController... | 脚手架默认/小项目 |
| **按业务域分包（本课程用）** | question/ 域内自建 entity/mapper/dto/vo/query/service/controller | 模块持续生长；**业务边界=包边界** |

理由：业务会持续生长；平铺后一搜 Service 全堆一起；分包后整块迁移/重构/讲解都舒服。附带规则可视化：**跨域只允许 Service 调 Service，禁止 Mapper 互调**。

**IDEA 操作**：右键 `com.wengu.backend` → **New → Package** → `question` → 右键 `question` 依次建子包：`entity mapper dto vo query service controller`（7 个，query 本章会用）。

### 模块一 · 分类（最小完整业务，热身）

**① 需求**：建分类、列出"我的所有分类 + 每类题数"（侧边栏用）。
**② 设计**：两个接口：`POST /category`（新建，重名拒绝）、`GET /category/list`（列表带计数）。
**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| Category.java | question/entity/ | 表映射实体 |
| CategoryMapper.java | question/mapper/ | MP一行流 |
| CategoryDTO.java | question/dto/ | 入参校验 |
| CategoryVO.java | question/vo/ | 出参（带题数） |
| CategoryVO 还需 PageResult | **common/result/**（第1章就有的包）| 分页统一出口，全项目复用 |
| CategoryService + Impl | question/service/ | 业务 |
| CategoryController | question/controller/ | 接口 |

```java
// common/result/PageResult.java —— 全项目复用的分页出口
package com.wengu.backend.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class PageResult<T> {
    private List<T> list;
    private long total;

    public static <T> PageResult<T> of(IPage<T> p) {
        return new PageResult<>(p.getRecords(), p.getTotal());
    }
}
```

```java
// question/entity/Category.java
package com.wengu.backend.question.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_category")
public class Category {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;          // ★ 业务表第一列=租户锚点，租户插件会自动往SQL里塞 user_id=?
    private String name;
    private Integer sort;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}
```

```java
// question/mapper/CategoryMapper.java
package com.wengu.backend.question.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.backend.question.entity.Category;

public interface CategoryMapper extends BaseMapper<Category> {}
```

```java
// question/dto/CategoryDTO.java
package com.wengu.backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryDTO {
    @NotBlank(message = "分类名不能为空")
    @Size(max = 50, message = "分类名最长50字")
    private String name;
}

// question/vo/CategoryVO.java（同包）
package com.wengu.backend.question.vo;

import lombok.Data;

@Data
public class CategoryVO {
    private Long id;
    private String name;
    private Integer sort;
    private Long questionCount;
}
```

```java
// question/service/CategoryService.java
package com.wengu.backend.question.service;

import com.baomidou.mybatisplus.spring.service.IService;   // 💣 3.5.17新家
import com.wengu.backend.question.dto.CategoryDTO;
import com.wengu.backend.question.entity.Category;
import com.wengu.backend.question.vo.CategoryVO;
import java.util.List;

public interface CategoryService extends IService<Category> {
    Long create(CategoryDTO dto);
    List<CategoryVO> listWithCount();
}

// question/service/CategoryServiceImpl.java
package com.wengu.backend.question.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;   // 💣 新家
import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.question.dto.CategoryDTO;
import com.wengu.backend.question.entity.Category;
import com.wengu.backend.question.entity.Question;
import com.wengu.backend.question.mapper.CategoryMapper;
import com.wengu.backend.question.mapper.QuestionMapper;
import com.wengu.backend.question.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    private final QuestionMapper questionMapper;

    @Override
    public Long create(CategoryDTO dto) {
        Long count = lambdaQuery().eq(Category::getName, dto.getName()).count();
        if (count > 0) throw new BizException(400, "分类已存在");
        Category c = new Category();
        c.setName(dto.getName());
        c.setSort(0);
        save(c);
        return c.getId();
    }

    @Override
    public List<CategoryVO> listWithCount() {
        // 分类全集（本租户的——租户插件自动过滤）
        List<Category> cats = list();
        // 每类题数：一条分组SQL（selectMaps 是MP做聚合查询的姿势）
        QueryWrapper<Question> qw = new QueryWrapper<Question>()
                .select("category_id", "count(*) AS cnt")
                .isNotNull("category_id")       // 💣 有它才排除"hlib分类"题的NULL分组行（用户抄稿丢过这行→NULL键longValue炸）
                .groupBy("category_id");
        Map<Long, Long> counter = questionMapper.selectMaps(qw).stream()
                .collect(Collectors.toMap(
                        m -> ((Number) m.get("category_id")).longValue(),
                        m -> ((Number) m.get("cnt")).longValue()));   // ★ row里拿数一律Number类，先转再 cast
        return cats.stream().map(c -> {
            CategoryVO v = new CategoryVO();
            v.setId(c.getId()); v.setName(c.getName()); v.setSort(c.getSort());
            v.setQuestionCount(counter.getOrDefault(c.getId(), 0L));
            return v;
        }).toList();
    }
}
```

```java
// question/controller/CategoryController.java
package com.wengu.backend.question.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.question.dto.CategoryDTO;
import com.wengu.backend.question.service.CategoryService;
import com.wengu.backend.question.vo.CategoryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping
    public Result<Long> create(@Validated @RequestBody CategoryDTO dto) {
        return Result.success(categoryService.create(dto));
    }

    @GetMapping("/list")
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.listWithCount());
    }
}
```

### 模块二 · 标签（识别"同构需求"，只写差异）

**① 需求**：列表看我的标签 + 录题时随手建标签。
**② 设计**：你先停 10 秒——这和分类是不是八成一样？教学点：**同构模块=复制结构调整差异，不重 invent**。差异点：标签常常不是"先建后用"，而是"录题时顺手创建" → 多一个 `createIfAbsent(name)`，且要**并发安全**。
**③ 缺什么（清单）**：

| 文件 | 建在哪 | 角色 |
|---|---|---|
| Tag.java | question/entity/ | **照 Category 抄，改四处**：@TableName("t_tag")、去掉 sort 字段 |
| TagMapper.java | question/mapper/ | 一行 |
| TagDTO.java | question/dto/ | name @NotBlank @Size(max=30) |
| TagVO.java | question/vo/ | id/name |
| TagService + Impl | question/service/ | 含 createIfAbsent |
| TagController | question/controller/ | POST /tag、GET /tag/list |

```java
// question/entity/Tag.java
package com.wengu.backend.question.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_tag")
public class Tag {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String name;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}
```

```java
// question/mapper/TagMapper.java
package com.wengu.backend.question.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.backend.question.entity.Tag;

public interface TagMapper extends BaseMapper<Tag> {}
```

```java
// question/dto/TagDTO.java（字段照 CategoryDTO 抄，Size(max=30)）
package com.wengu.backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TagDTO {
    @NotBlank(message = "标签名不能为空")
    @Size(max = 30, message = "标签名最长30字")
    private String name;
}

// question/vo/TagVO.java（同包）
package com.wengu.backend.question.vo;

import lombok.Data;

@Data
public class TagVO {
    private Long id;
    private String name;
}
```

```java
// question/service/TagService.java
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

// question/service/TagServiceImpl.java
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
            TagVO v = new TagVO(); v.setId(t.getId()); v.setName(t.getName());
            return v;
        }).toList();
    }
}

```java
// question/controller/TagController.java
package com.wengu.backend.question.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.question.dto.TagDTO;
import com.wengu.backend.question.service.TagService;
import com.wengu.backend.question.entity.Tag;
import com.wengu.backend.question.vo.TagVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/tag")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @PostMapping                           // 录题时随手建标签实则调 createIfAbsent
    public Result<TagVO> create(@Validated @RequestBody TagDTO dto) {
        Tag tag = tagService.createIfAbsent(dto.getName());
        TagVO v = new TagVO();
        v.setId(tag.getId()); v.setName(tag.getName());
        return Result.success(v);          // 出参一律 VO：绝不把实体原样端出去
    }

    @GetMapping("/list")                   // 侧边标签池，过滤筛选用
    public Result<List<TagVO>> list() {
        return Result.success(tagService.listMine());
    }
}
```


> ★ 抽查要点：为什么"代码先查重"鱼"唯一索引兜底"要**两层都上**？（答：代码查重=快路径防常规重名报错友好；唯一索引=并发窗口的最终真理。以后秒杀防重/幂等同理。）

### 模块三 · 题目主模块（真正垛口）

**① 需求**：建题（标题+答案+分类+标签集+类型+来源+难度）；分页筛选列表（不要答案全文）；详情；编辑（重关联标签）；删除。
**② 设计要点**：
- 建题/改题 = **三处写库**（题目 + n 条关联 + 可能新建 tag）→ 事务 `@Transactional(rollbackFor = Exception.class)`（★ 默认只回滚 RuntimeException）
- 列表/详情拆 VO：QuestionVO 无 answerMd（MEDIUMTEXT 别在列表拖血）；QuestionDetailVO 才有
- 越权零手写：detail/delete 的 SQL 被租户插件自动带 user_id=? → 他人数据=查无 → 404
- 标签筛选走**两段法**（先关联表收 question_id 集合，再 in 主表）——躲开"JOIN + 租户改写"的别名边缘坑，债登记章末
- ❗ 关联表没有单列主键 → BaseMapper 上不了 → **自定义 SQL 一体化到 QuestionTagMapper**

**③ 缺什么（清单）**：

| 文件 | 角色 |
|---|---|
| Question.java（entity） | 主表实体 |
| QuestionTag.java（entity，桥表最小PO） | join 批量取关联用 |
| QuestionMapper.java | 一行 |
| QuestionTagMapper.java | 关联表自定义SQL（link/unlinkAll/tagsOf/linkPage...） |
| QuestionCreateDTO / QuestionUpdateDTO | 建题/改题入参 |
| QuestionPageQuery | 分页筛选 |
| QuestionVO（列表，无answerMd）/ QuestionDetailVO（详情，含） | 出参 |
| QuestionService + Impl | 业务 |
| QuestionController | 五接口 |

```java
// question/entity/Question.java
package com.wengu.backend.question.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_question")
public class Question {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long categoryId;
    private String title;
    private String answerMd;
    private Integer qType;        // 0八股 1手撕 2主观 3笔试
    private String source;
    private Integer difficulty;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}

// question/entity/QuestionTag.java（桥表PO，两张表都必须手动，无逻辑删/无填充）
package com.wengu.backend.question.entity;

import lombok.Data;

@Data
public class QuestionTag {
    private Long questionId;
    private Long tagId;
}
```

```java
// question/mapper/QuestionTagMapper.java —— 桥表没有单列主键，BaseMapper 不适用于它
package com.wengu.backend.question.mapper;

import com.wengu.backend.question.entity.QuestionTag;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface QuestionTagMapper {

    @Insert("insert into t_question_tag(question_id, tag_id) values(#{qid}, #{tid})")
    int link(@Param("qid") Long qid, @Param("tid") Long tid);

    @Delete("delete from t_question_tag where question_id = #{qid}")
    int unlinkAll(@Param("qid") Long qid);

    @Select("select tag_id from t_question_tag where question_id = #{qid}")
    List<Long> tagIdsOf(@Param("qid") Long qid);

    @Select("select distinct question_id from t_question_tag where tag_id = #{tid}")
    List<Long> questionIdsOf(@Param("tid") Long tid);

    /** 批量取关联（一次捞一整页的关联，杀死列表N+1）
     *  💣 写法注意：<script> 必须是字符串最开头，前后不能有空白/换行，
     *  所以这里用字符串拼接而不用 text block（text block 首行是换行=运行时炸） */
    @Select("<script>"
            + " select question_id, tag_id from t_question_tag"
            + " where question_id in"
            + " <foreach collection='qids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<QuestionTag> linksOf(@Param("qids") List<Long> qids);
}
```

```java
// question/dto/QuestionCreateDTO.java
package com.wengu.backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class QuestionCreateDTO {
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长100字")
    private String title;
    @NotBlank(message = "答案不能为空")
    private String answerMd;
    private Long categoryId;
    private List<String> tagNames;         // 前端传名字，后端负责"不存在就建"
    private Integer qType;                 // 0-3，默认0
    private String source;
    private Integer difficulty;            // 0-3，默认0
}

// question/dto/QuestionUpdateDTO.java（同包，完整体；和Create的区别=多了主键id、少了"来源默认"零食）
package com.wengu.backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class QuestionUpdateDTO {
    @NotNull(message = "id不能为空")
    private Long id;
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长100字")
    private String title;
    @NotBlank(message = "答案不能为空")
    private String answerMd;
    private Long categoryId;
    private List<String> tagNames;
    private Integer qType;
    private String source;
    private Integer difficulty;
}
```

```java
// question/query/QuestionPageQuery.java
package com.wengu.backend.question.query;

import lombok.Data;
import java.util.List;

@Data
public class QuestionPageQuery {
    private Long pageNum = 1L;
    private Long pageSize = 10L;
    private String keyword;
    private Long categoryId;
    private List<Long> tagIds;       // 任一命中即入选（OR 语义）
    private Integer qType;
}

// question/vo/QuestionVO.java（列表——注意没有 answerMd）
package com.wengu.backend.question.vo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class QuestionVO {
    private Long id;
    private String title;
    private Long categoryId;
    private String categoryName;
    private List<String> tagNames;
    private Integer qType;
    private String source;
    private Integer difficulty;
    private LocalDateTime updatedTime;
}

// question/vo/QuestionDetailVO.java（同包——完整版）
package com.wengu.backend.question.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
public class QuestionDetailVO {
    private Long id;
    private String title;
    private String answerMd;
    private Long categoryId;
    private String categoryName;
    private List<String> tagNames;
    private Integer qType;
    private String source;
    private Integer difficulty;
    private LocalDateTime updatedTime;
}
```

```java
// question/service/QuestionService.java
package com.wengu.backend.question.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.wengu.backend.common.result.PageResult;
import com.wengu.backend.question.dto.*;
import com.wengu.backend.question.entity.Question;
import com.wengu.backend.question.query.QuestionPageQuery;
import com.wengu.backend.question.vo.*;

public interface QuestionService extends IService<Question> {
    Long create(QuestionCreateDTO dto);
    void update(QuestionUpdateDTO dto);
    PageResult<QuestionVO> page(QuestionPageQuery q);
    QuestionDetailVO detail(Long id);
    void delete(Long id);
}

// question/service/QuestionServiceImpl.java（完整可抄）
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
import com.wengu.backend.question.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements QuestionService {

    private final TagService tagService;
    private final CategoryService categoryService;
    private final QuestionTagMapper questionTagMapper;

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
            // 💣 Map.of() 不可变Map连 get(null) 都抛 NPE——先判空再查（用户实测踩中）
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
    }
}
```

```java
// question/controller/QuestionController.java
package com.wengu.backend.question.controller;

import com.wengu.backend.common.result.PageResult;
import com.wengu.backend.common.result.Result;
import com.wengu.backend.question.dto.*;
import com.wengu.backend.question.query.QuestionPageQuery;
import com.wengu.backend.question.service.QuestionService;
import com.wengu.backend.question.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/question")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @PostMapping
    public Result<Long> create(@Validated @RequestBody QuestionCreateDTO dto) {
        return Result.success(questionService.create(dto));
    }

    @PutMapping
    public Result<Void> update(@Validated @RequestBody QuestionUpdateDTO dto) {
        questionService.update(dto);
        return Result.success();
    }

    @GetMapping("/page")
    public Result<PageResult<QuestionVO>> page(QuestionPageQuery query) {
        return Result.success(questionService.page(query));
    }

    @GetMapping("/{id}")
    public Result<QuestionDetailVO> detail(@PathVariable Long id) {
        return Result.success(questionService.detail(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return Result.success();
    }
}
```

### 💣 第3章必改：tenant 的 INSERT 需要 user_id 补头法（已落地，见修订痕迹）

实测结论（2026-10-18）：**多租户 TenantLineInnerInterceptor 的“加 WHERE”方向自动，INSERT 靠 MetaObjectHandler 自动填充补 userId**。三个落地痕迹：

1. `MybatisPlusConfig.ignoreTable` 已扩为 `Set.of("t_user", "t_question_tag")`（桥表无 user_id 列）
2. `UserContext` 补了标准 `get()`（读可不空；断言用 requireUserId）
3. **急切求值教训**：补头第一版直接把 `UserContext.requireUserId()` 当参数——register 场景无登录人 → 异常穿成 500（T1 实炸）。正确姿势先判空：

```java
Long userId = UserContext.get();
if (userId != null) strictInsertFill(metaObject, "userId", Long.class, userId);
```

### 模块四 · 图片上传

**① 需求**：编辑答案想贴图；上传后返回 `/media/...` 的 URL，Markdown 里引用并在详情页显示。
**② 设计**：`POST /media/upload`（MultipartFile）→ 校验（非空/≤5MB/白名单后缀）→ `data/media/yyyyMM/{uuid}.{ext}` → 返回 `{url:"/media/yyyyMM/xx.png"}`。图片 v1 不入库（省租户链路），文物关联 controller 文件。UUID 头？下一招（★为假 préfuster 的"UUID 文件名"）。
**③ 缺什么**：`MediaController` + `MediaVO` + 配置 + 静态映射。

```properties
# application.properties 追加（dir 用正斜杠）
wengu.media-dir=D:/JavaCode/wengu/data/media
```

```java
// question/controller/MediaController.java
package com.wengu.backend.question.controller;

import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.common.result.Result;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/media")
public class MediaController {

    @Data
    @AllArgsConstructor
    public static class MediaVO {           // 内嵌VO也行，能省一个文件
        private String url;
    }

    @Value("${wengu.media-dir}")
    private String mediaDir;

    private static final Set<String> ALLOWED = Set.of("png", "jpg", "jpeg", "webp", "gif");

    @PostMapping("/upload")
    public Result<MediaVO> upload(@RequestParam("file") MultipartFile f) {
        if (f.isEmpty()) throw new BizException(400, "空文件");
        if (f.getSize() > 5 * 1024 * 1024) throw new BizException(400, "单张图片最大5MB");
        String name = f.getOriginalFilename();
        int i = (name == null) ? -1 : name.lastIndexOf('.');
        String ext = (i < 0 || i == name.length() - 1) ? null : name.substring(i + 1).toLowerCase();
        if (ext == null || !ALLOWED.contains(ext)) throw new BizException(400, "仅支持 png/jpg/jpeg/webp/gif");
        // 为什么UUID+yyyyMM：防原名冲突/防路径穿越（panic ../）/防目录爆炸（按月分）
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        try {
            Path dir = Paths.get(mediaDir, month);
            Files.createDirectories(dir);
            String fileName = UUID.randomUUID() + "." + ext;   // ★ 存盘与回参共用同一个名
            Files.copy(f.getInputStream(), dir.resolve(fileName));
            return Result.success(new MediaVO("/media/" + month + "/" + fileName));
        } catch (Exception e) {
            throw new BizException(500, "上传失败");
        }
    }
}
```

> 💣 修订 trace（诚实记录）：上一版这里存盘与回参各拿了一次 `UUID.randomUUID()`——**两个 UUID 必然不同 → 返回的 URL 指向一个根本不存在的文件**。已修为一个 `fileName` 变量共用。★ 验收抽问改成：UUID 重命名 + yy-MM 目录双保险各防什么？

`config/WebMvcConfig.java` 里**追加一个方法**（"打开已有文件追加方法"）：

```java
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/media/**")
                .addResourceLocations("file:D:/JavaCode/wengu/data/media/");
    }
```

## 🖥️ 前端（第 2 次会话）

**① 需求**：登录后的家 = 侧边分类 + 题目卡片区；编辑用 Markdown 编辑器带插图上传；详情只读。
**② 设计**：`HomeView` 升级成"登录后的冰箱贴车门"；新过去三页+改路由。路由外滩：/question/list（默认页）、/question/new（建）、/question/:id/edit（改）、/question/:id（详情）。
**③ 缺什么**：

```
src/
├── api/question.ts     api/category.ts     api/tag.ts
├── views/HomeView.vue                 重写：布局壳（aside分类+main出router-view）
├── views/QuestionListView.vue         筛选+卡+分页
├── views/QuestionEditView.vue         md-editor-v3 + 分类/标签
└── views/QuestionDetailView.vue       previewOnly
```

```ts
// api/question.ts
import request from './request'
export const pageQuestion = (q: any) => request.get('/question/page', { params: q })
export const detailQuestion = (id: number) => request.get(`/question/${id}`)
export const createQuestion = (d: any) => request.post('/question', d)
export const updateQuestion = (d: any) => request.put('/question', d)
export const deleteQuestion = (id: number) => request.delete(`/question/${id}`)
    // hmm 以上是"统一 request.ts"的姿势（form 上传除外)
// api/category.ts —— createCategory / listCategory；api/tag.ts —— createTag / listTag
```

**编辑器插图上传 hook**（md-editor-v3 的 onUploadImg：中级姿势=跟走统一instance——⚠️ 因 request.ts 拆了一层数，这里注意 FormData 需要自己带 token）：

```ts
const onUploadImg = async (files: any, callback: (urls: any) => void) => {
  const fd = new FormData()
  fd.append('file', files[0] as File)
  const resp = await fetch('/api/media/upload', {
    method: 'POST',
    headers: { Authorization: `Bearer ${useUserStore().token}` },
    body: fd,
  })
  const j = await resp.json()
  if (j.code === 200) callback([{ url: j.data.url, alt: '', title: '' }])
}
```

其余三页照课程文件树抄（表格/卡/表单的骨架在第 4 章还会系统精修，本章目标是"能用"）。

## 🧪 测试与验收

### 验收记录（2026-10-18 · AI 全量跑测，15/15 绿）

亮点实录：
- EXPLAIN：type=ref，key=idx_user_category，rows=1（const,const）→ 联合索引完全命中
- 越权 T12 踩过一次断言层陷阱：`BizException(404)` 出口是 **HTTP 200 + body.code=404**（Result 统一出口设计使然）→ 断言必须读 body 不能只看 HTTP 状态码；401 是唯一例外（拦截器直接 setStatus(401)）
- 双 UUID 坑修复后实测：上传返回的 URL 真实可回读（HTTP 200）

四个新坑入册（详见 `开发规划.md` FAQ）：
1. fill/过滤类的工具参数**急切求值**会炸未登录场景（判空再传）
2. 断言分"HTTP 层"与"body 层"，写用例前想清楚看哪层
3. Windows curl 里带中文/转义的 body 一律**写文件 + --data-binary @file** 投递，别裸敲
4. IDEA 对"依赖包结构重构"（如 MP 3.5.17）容易报**索引幽灵冲突**：javap/mvn 是仲裁者，Maven Reload + Rebuild 消
5. **雪花ID精度丢失**（浏览器"题目不存在"但 API 测试通过的经典）：id>JS 安全整数（9007199254740991）→ JSON 数字解析即变形 → 后缀已落 `config/JacksonConfig`：所有 Long 序列化 String 输出（Jackson3 坑：`ToStringSerializer.instance` 是小写，addSerializer(Long.TYPE,…) 也要覆盖）；curl/pgAdmin 测试测不出此坑，**必须在浏览器验**
6. **前端也要零数字 id**（编辑页 404 第二弹，同理同族）：任何 `Number(id)`/`parseInt` 都炸精度——id/分类长数字全链路（路由参数/query/el-select value）保持字符串，后端 Spring 参数绑定自动转 Long。**分工哲学：后端存 Long（精度+索引效率），传输层用字符串，前端碰都不碰数字**——你在编码时看到"id 都定义成 Long"不必怀疑后端设计贵宗，错的是转换时机
7. **布局壳数据陈旧**（"测试分类总数0但题目在里面"）：HomeView 挂载时拉分类计数 → 题目增删/移动后侧栏全旧。修法=watch route.fullPath 导航级刷新。另外本例 API 还白送一个租户教学彩蛋：用其他账号查分类列表，连"测试分类"的存在都看不到（隔离彻底）

**用例表（Apifox 归档用）**：

| # | 用例 | 期望 |
|---|---|---|
| T1 | POST /category ×2（新 + 重复）+ 超长名 | 200 / 400 已存在 / 400 校验 |
| T2 | GET /category/list | 计数=随建随查的题数相符 |
| T3 | POST /tag ×2 新同 | 200/200，重复返回同 id（createIfAbsent） |
| T4 | GET /tag/list | 只见本账号标签 |
| T5 | POST /question 带2标签+分类+来源 | 200 id；t_question 有行(user_id 已填)；t_question_tag 2行 |
| T6 | user_id 断言（DB直查） | INSERT 后 user_id=1521550402202888192 |（你的账号id非同此） |
| T7 | GET /question/page keyword 端到端 / 分类过/ tagIds 过/ qType 过 | 各筛选独立有效/可叠加 |
| T8 | 无 answerMd 泄露 | page 返回体里查无 answerMd 字段 |
| T9 | detail | 全字段含 categoryName/tagNames |
| T10 | PUT 换分类+换标签 | detail 立即反映，旧关联消失 |
| T11 | DELETE | 列表不见；DB deleted=1；关联清0 |
| T12 | **越权**：B查看A的题 | 404 |
| T13 | 假文件(.exe)/超5MB | 400 |
| T14 | EXPLAIN type=ref, key=idx_user_category | 走索引 |
| T15 | tagIds=空列表不传 | 全量分页不炸（两段法空集短路） |

**验收清单**：12条用例 ✅ / 浏览器完成主角故事 ✅ / EXPLAIN 截图 ✅ / 无500 ✅ / commit（`feat(phase2): 题目域CRUD与图片上传`）+push ✅

> 债登记（下章或之后还处理的官方账本）：
> - N+1 组装：page 的 批量组装已实装（linksOf 杀整页级 N+1），detail 层级单查询可接受
> - tagIds 两段法 vs JOIN：两段法目前占优，第 7 章讨论 JOIN+租户改写别名问题后可复评
> - element-plus 全量包 >500kB：第 9 章按需导入收掉

## 📦 本章交付物

后端：PageResult + question 域 17 文件 + MediaController + WebMvcConfig 新增 + MetaObjectHandler 补 userId 填充 + ignoreTable 扩展 + application.properties 加 media-dir。
前端：3 个 api + 4 页 + 布局改版 + 路由。

## 🔗 关联八股

联合索引最左前缀与 EXPLAIN（type/rows/key）；事务 rollbackFor 与传播行为入门；N+1 查询问题（今天亲眼看见并被批量法治理）；逻辑删除 vs 物理删除（题目软删/桥表硬删的混用理由）；UUID 文件名防冲突与路径穿越；后缀白名单。

## 下章预告

**04 · 前端体验专题**：把这批"能跑"页面变成"好用的产品"。防抖搜索、骨架屏、手机视口精修、空状态兜底——以及一道真正值得思考的题：为什么列表页加载策略会反过来影响后端缓存设计。
