// security/LoginInterceptor.java
package com.wengu.backend.security;

import com.wengu.backend.common.util.JwtUtil;
import com.wengu.backend.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler)
            throws Exception {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return deny(resp);
        Long userId = JwtUtil.parse(auth.substring(7));
        if (userId == null) return deny(resp);
        UserContext.set(userId);          // ★ 本请求线程后面的代码都"知道"你是谁
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp,
                                Object handler, Exception ex) {
        UserContext.clear();              // ★ Tomcat线程池会复用线程，不清=串号，内存泄漏
    }

    private boolean deny(HttpServletResponse resp) throws java.io.IOException {
        resp.setStatus(401);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getOutputStream().write(
                "{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}"
                        .getBytes(StandardCharsets.UTF_8));
        return false;
    }
}