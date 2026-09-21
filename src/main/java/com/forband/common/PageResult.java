package com.forband.common;

import java.util.List;

/** 分页响应，形状见 docs/10：data = {list, total, page, size}，page 从 1 起。 */
public record PageResult<T>(List<T> list, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(list, total, page, size);
    }
}
