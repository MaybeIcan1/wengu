// config/MybatisPlusConfig.java
package com.wengu.backend.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.wengu.backend.common.util.UserContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.LocalDateTime;
import java.util.Set;

@Configuration
@MapperScan("com.wengu.backend.**.mapper")   // 扫描所有模块下的mapper包
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // ① 多租户插件：所有SQL自动拼 WHERE user_id = ?（数据隔离的大杀器）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(UserContext.requireUserId());
            }
            @Override
            public String getTenantIdColumn() { return "user_id"; }
            @Override
            public boolean ignoreTable(String tableName) {
                // 没有user_id列的表要跳过：t_user是"租户本体"，t_question_tag是纯桥表
                return Set.of("t_user", "t_question_tag").contains(tableName);
            }
        }));
        // ② 分页插件（第3章的列表接口用）
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /** created_time/updated_time 自动填充 */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                strictInsertFill(metaObject, "createdTime", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(metaObject, "updatedTime", LocalDateTime.class, LocalDateTime.now());
                // 💣 租户INSERT补头（已修的坑）：v1版本直接把 UserContext.requireUserId() 当参数传入——
                // register 场景没有登录人 → 未登录异常抛穿成500（急切求值教训）。
                // 正确姿势：先取值判空，只有真实登录过才补。
                Long userId = UserContext.get();
                if (userId != null) {
                    strictInsertFill(metaObject, "userId", Long.class, userId);
                }
            }
            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}