// security/controller/UserController.java
package com.wengu.backend.security.controller;

import com.wengu.backend.common.result.Result;
import com.wengu.backend.common.util.UserContext;
import com.wengu.backend.security.dto.*;
import com.wengu.backend.security.service.UserService;
import com.wengu.backend.security.vo.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping("/register")
    public Result<Void> register(@Validated @RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.success();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Validated @RequestBody LoginDTO dto) {
        return Result.success(userService.login(dto));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.success(userService.me(UserContext.requireUserId()));
    }

    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@Validated @RequestBody UpdateProfileDTO dto) {
        return Result.success(userService.updateProfile(UserContext.requireUserId(), dto));
    }
}