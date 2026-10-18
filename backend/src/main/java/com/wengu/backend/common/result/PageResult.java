package com.wengu.backend.common.result;


import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class PageResult<T> {
    private List<T> list;
    private long total;
    //静态方法，会在对象生成之前就调用，所以要先知道泛型是什么
    public static <T> PageResult<T> of(IPage<T> p){
        //            ↑         ↑              ↑
        //     方法声明泛型T   返回值类型      参数类型
        //                    （依赖T）      （依赖T）
        return new PageResult<>(p.getRecords(),p.getTotal());
    }
}
