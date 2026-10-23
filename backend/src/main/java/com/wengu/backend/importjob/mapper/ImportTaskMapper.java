// importjob/mapper/ImportTaskMapper.java
package com.wengu.backend.importjob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.backend.importjob.entity.ImportTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ImportTaskMapper extends BaseMapper<ImportTask> {

    /** ★ CAS：排队态→解析中；重复消费在此被挡（返回0=别人已推进） */
    @Update("update t_import_task set status = 1, updated_time = now() " +
            "where id = #{id} and status = 0 and deleted = 0")
    int markParsing(@Param("id") Long id);

    /** 待确认→导入中；确认按钮的并发防重（狂点两下只成功一次） */
    @Update("update t_import_task set status = 3, updated_time = now() " +
            "where id = #{id} and status = 2 and deleted = 0")
    int markImporting(@Param("id") Long id);
}