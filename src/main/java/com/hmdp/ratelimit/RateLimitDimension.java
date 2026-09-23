package com.hmdp.ratelimit;

/**
 * 限流标识维度：决定桶 key 用什么来区分请求方。
 */
public enum RateLimitDimension {

    /** 全站共用一个桶，用于保护下游系统总容量 */
    GLOBAL,

    /** 按客户端 IP，用于未登录接口的爬虫防护 */
    IP,

    /** 按登录用户；未登录时退化为 IP，避免所有匿名请求挤进同一个桶 */
    USER,

    /** 按方法参数取值（如手机号），用于防短信轰炸、防单账号爆破 */
    PARAM,

    /** IP + 方法参数 */
    IP_AND_PARAM,

    /** 登录用户 + 方法参数 */
    USER_AND_PARAM
}
