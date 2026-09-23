package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.ratelimit.RateLimit;
import com.hmdp.ratelimit.RateLimitDimension;
import com.hmdp.ratelimit.RateLimitRule;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.trace.ApiTrace;
import com.hmdp.utils.UserHolder;
import io.reactivex.Single;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;


@Slf4j
@ApiTrace
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Autowired
    private StringRedisTemplate redisTemplate;


    @PostMapping("code")
    @RateLimit({
            @RateLimitRule(key = "user:code:phone", dimension = RateLimitDimension.PARAM, paramName = "phone",
                    capacity = 1, refillTokens = 1, refillPeriodMillis = 60_000L, failOpen = false,
                    message = "验证码发送过于频繁，请稍后再试"),
            @RateLimitRule(key = "user:code:ip", dimension = RateLimitDimension.IP,
                    capacity = 10, refillTokens = 10, refillPeriodMillis = 60_000L, failOpen = false,
                    message = "当前网络请求过于频繁，请稍后再试")
    })
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone,session);
    }




    @PostMapping("/login")
    @RateLimit({
            @RateLimitRule(key = "user:login:phone", dimension = RateLimitDimension.PARAM, paramName = "phone",
                    capacity = 5, refillTokens = 5, refillPeriodMillis = 60_000L,
                    message = "登录尝试过于频繁，请稍后再试"),
            @RateLimitRule(key = "user:login:ip", dimension = RateLimitDimension.IP,
                    capacity = 60, refillTokens = 60, refillPeriodMillis = 60_000L)
    })
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        return userService.login(loginForm,session);
    }

    @PostMapping("/logout")
    public Result logout(){
        UserHolder.removeUser();
        return Result.fail("功能未完成");
    }

    @GetMapping("/me")
    public Result me(){
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    @RateLimit(@RateLimitRule(key = "user:info", dimension = RateLimitDimension.IP,
            capacity = 30, refillTokens = 20, refillPeriodMillis = 1_000L))
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    @GetMapping("/{id}")
    @RateLimit(@RateLimitRule(key = "user:detail", dimension = RateLimitDimension.IP,
            capacity = 30, refillTokens = 20, refillPeriodMillis = 1_000L))
    public Result queryUserById(@PathVariable("id") Long userId){
        //查询详情
        User user = userService.getById(userId);
        if(user==null){
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }


    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }


    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }
}



