// common/result/Result.java
package com.wengu.backend.common.result;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;   // 200=成功 400=参数/业务错 401=未登录 429=限流 500=系统错
    private String message;
    private T data;

    public static <T> Result<T> success()              { return build(200, "ok", null); }
    public static <T> Result<T> success(T data)        { return build(200, "ok", data); }
    public static <T> Result<T> fail(int code, String msg) { return build(code, msg, null); }
    private static <T> Result<T> build(int code, String msg, T data) {
        Result<T> r = new Result<>();
        r.code = code; r.message = msg; r.data = data;
        return r;
    }
}