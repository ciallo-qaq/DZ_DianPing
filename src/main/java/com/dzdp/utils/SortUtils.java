package com.dzdp.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 把"按指定 id 顺序返回结果"这件事从数据库挪到内存里做。
 *
 * <p>原来是用 {@code ORDER BY FIELD(id, ...)} 实现的，属于函数排序：数据库无法利用索引、
 * 必然产生 filesort，而且要靠字符串拼接 SQL。改成"主键 in 查询 + 内存重排"之后，
 * 查询本身走主键索引，也不需要再拼接 SQL。</p>
 */
public final class SortUtils {

    private SortUtils() {
    }

    /**
     * 按 ids 给出的顺序重排 list；ids 里查不到的元素直接跳过，保持原来的语义。
     *
     * @param list      数据库按任意顺序返回的结果
     * @param ids       期望的顺序（一般来自 Redis，如点赞时间倒序的用户 id）
     * @param idGetter  从元素里取出 id 的方法，例如 {@code UserDTO::getId}
     */
    public static <T> List<T> sortByIds(List<T> list, List<Long> ids, Function<T, Long> idGetter) {
        Map<Long, T> map = new HashMap<>(Math.max(list.size() * 2, 4));
        for (T item : list) {
            map.put(idGetter.apply(item), item);
        }
        List<T> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            T item = map.get(id);
            if (item != null) {
                ordered.add(item);
            }
        }
        return ordered;
    }
}
