// security/vo/LoginVO.java
package com.wengu.backend.security.vo;
import lombok.*;
@Getter @AllArgsConstructor
public class LoginVO {
    private String token;
    private Long userId;
    private String nickname;
}