package com.dzdp.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = "D:\\tlias\\nginx-1.18.0 dianping\\html\\hmdp\\imgs";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;

    /** 允许的最大页码：防止 current 传入超大值造成深分页（LIMIT 大偏移量）把数据库打满 */
    public static final int MAX_PAGE_INDEX = 100;

    /**
     * 页码兜底：null 或小于 1 归一到 1，超过 {@link #MAX_PAGE_INDEX} 截断。
     * current 由前端传入，不做兜底时可以直接传 1000000 触发深分页。
     */
    public static int safeCurrentPage(Integer current) {
        if (current == null || current < 1) {
            return 1;
        }
        return Math.min(current, MAX_PAGE_INDEX);
    }
}
