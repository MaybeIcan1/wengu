// security/service/UserServiceImpl.java（核心业务全在这，先读懂再抄）
package com.wengu.backend.security.service;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.wengu.backend.security.dto.*;
import com.wengu.backend.security.entity.User;
import com.wengu.backend.security.mapper.UserMapper;
import com.wengu.backend.security.vo.*;
import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    @Override
    public void register(RegisterDTO dto) {
        // 1) 业务校验（撞库读密码法：登录时统一报"用户名或密码错误"）
        Long count = lambdaQuery().eq(User::getUsername, dto.getUsername()).count();
        if (count > 0) throw new BizException(400, "用户名已被占用");
        // 2) 组装 + 加密 + 落库
        User u = new User();
        u.setUsername(dto.getUsername());
        u.setPassword(encoder.encode(dto.getPassword()));   // BCrypt: 明文进不了库
        u.setNickname(dto.getNickname() == null ? dto.getUsername() : dto.getNickname());
        save(u);
    }
    @Override
    public LoginVO login(LoginDTO dto) {
        User user = lambdaQuery().eq(User::getUsername, dto.getUsername()).one();
        if (user == null || !encoder.matches(dto.getPassword(), user.getPassword()))
            throw new BizException(400, "用户名或密码错误");
        return new LoginVO(JwtUtil.gen(user.getId()), user.getId(), user.getNickname());
    }
    @Override
    public UserVO me(Long userId) {
        User u = getById(userId);
        if (u == null) throw new BizException(401, "用户不存在");
        return UserVO.builder()
                .id(u.getId()).username(u.getUsername())
                .nickname(u.getNickname()).email(u.getEmail())
                .build();                                  // ★ password 永远不进 VO
    }

    @Override
    public UserVO updateProfile(Long userId, UpdateProfileDTO dto) {
        User u = getById(userId);
        if (u == null) throw new BizException(401, "用户不存在");
        u.setNickname(dto.getNickname().trim());
        String email = dto.getEmail() == null ? null : dto.getEmail().trim();
        u.setEmail(email == null || email.isEmpty() ? null : email);
        updateById(u);
        return me(userId);
    }
}