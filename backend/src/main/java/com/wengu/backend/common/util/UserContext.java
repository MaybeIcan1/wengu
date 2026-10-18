// common/util/UserContext.java
package com.wengu.backend.common.util;

import com.wengu.backend.common.exception.BizException;

public class UserContext {
    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    public static void set(Long userId) { HOLDER.set(userId); }
    /** 读取当前登录人（可为null——用于"可有可无"的场景，如租户INSERT补头；断言必须存在时用 requireUserId） */
    public static Long get()            { return HOLDER.get(); }
    public static void clear()          { HOLDER.remove(); }
    public static Long requireUserId() {
        Long id = HOLDER.get();
        if (id == null) throw new BizException(401, "未登录或登录已过期");
        return id;
    }
}