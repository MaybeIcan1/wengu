package com.wengu.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 💣 全局大坑（实测踩中）：雪花ID(如2111834443127214081) > JS Number.MAX_SAFE_INTEGER(9007199254740991)，
 * JSON数字到浏览器解析时精度丢失 → 详情接口拿变形id → 404"题目不存在"。
 * 解法：所有 Long 输出为字符串（JS 侧字符串无精度问题，前端的 api 层已按字符串传参无感兼容）。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JacksonModule longToStringModule() {
        SimpleModule m = new SimpleModule("long-to-string");
        m.addSerializer(Long.class, ToStringSerializer.instance);   // Jackson3 是小写 instance（2时代是INSTANCE）
        m.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return m;
    }
}
