// security/service/UserService.java
package com.wengu.backend.security.service;
import com.baomidou.mybatisplus.spring.service.IService;
import com.wengu.backend.security.dto.*;
import com.wengu.backend.security.entity.User;
import com.wengu.backend.security.vo.*;
public interface UserService extends IService<User> {
    void register(RegisterDTO dto);
    LoginVO login(LoginDTO dto);
    UserVO me(Long userId);
}