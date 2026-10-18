package com.wengu.backend.config;

import com.wengu.backend.security.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/login",      // 登录/注册不能拦
                        "/user/register",
                        "/media/*/*",       // 静态图片开放（/media/202610/xx.png 两段式）；
                                            // 注意 /media/upload（一段）不在内 → 上传仍需登录 ★
                        "/actuator/**",
                        "/error"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/media/**")
                .addResourceLocations("file:D:/JavaCode/wengu/data/media/");
    }
}
