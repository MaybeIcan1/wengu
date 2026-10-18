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

    /** 批量取关联（一次捞一整页的关联，杀死列表N+1）*/
    @Select("<script>"
            + " select question_id, tag_id from t_question_tag"
            + " where question_id in"
            + " <foreach collection='qids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<QuestionTag> linksOf(@Param("qids") List<Long> qids);
}