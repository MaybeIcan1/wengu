package com.wengu.backend.security.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// security/dto/RegisterDTO.java —— 同包
@Data
public class RegisterDTO {
    @NotBlank @jakarta.validation.constraints.Pattern(
            regexp = "^\\w{3,20}$", message = "用户名3~20位字母数字下划线")
    private String username;
    @NotBlank(message = "密码不能为空")
    @jakarta.validation.constraints.Size(min = 6, max = 32, message = "密码6~32位")
    private String password;
    private String nickname;   // 可空，默认=用户名
}