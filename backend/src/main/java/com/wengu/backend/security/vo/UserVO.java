package com.wengu.backend.security.vo;

import lombok.Builder;
import lombok.Getter;

// security/vo/UserVO.java —
@Getter
@Builder
public class UserVO {
    private Long id;
    private String username;
    private String nickname;
    private String email;
}