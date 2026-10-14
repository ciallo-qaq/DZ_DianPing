package com.dzdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dzdp.dto.Result;
import com.dzdp.dto.UserDTO;
import com.dzdp.entity.Blog;
import com.dzdp.ratelimit.RateLimit;
import com.dzdp.ratelimit.RateLimitDimension;
import com.dzdp.ratelimit.RateLimitRule;
import com.dzdp.service.IBlogService;
import com.dzdp.service.IUserService;
import com.dzdp.trace.ApiTrace;
import com.dzdp.utils.SystemConstants;
import com.dzdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;


@ApiTrace
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
       return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.updateLike(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {

        UserDTO user = UserHolder.getUser();

        // 列表页不需要正文（content 单列 2048），显式指定列减少传输与 IO；
        // 补上 ORDER BY 让分页顺序稳定，并命中 idx_user_create(user_id, create_time)
        Page<Blog> page = blogService.query()
                .select("id", "shop_id", "user_id", "title", "images", "liked", "comments", "create_time")
                .eq("user_id", user.getId())
                .orderByDesc("create_time", "id")
                .page(new Page<>(SystemConstants.safeCurrentPage(current), SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }


    @GetMapping("/hot")
    @RateLimit(@RateLimitRule(key = "blog:hot", dimension = RateLimitDimension.IP,
            capacity = 30, refillTokens = 20, refillPeriodMillis = 1_000L))
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
       return blogService.queryHotBlog(current);
    }


    @GetMapping("/{id}")
    @RateLimit(@RateLimitRule(key = "blog:detail", dimension = RateLimitDimension.IP,
            capacity = 30, refillTokens = 20, refillPeriodMillis = 1_000L))
    public Result queryById(@PathVariable("id") Long id){
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    @RateLimit(@RateLimitRule(key = "blog:likes", dimension = RateLimitDimension.IP,
            capacity = 30, refillTokens = 20, refillPeriodMillis = 1_000L))
    public Result queryBlogLikes(@PathVariable("id") Long id){
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current",defaultValue = "1") Integer currrent,
            @RequestParam("id") Long id){
        //根据用户查询
        Page<Blog> page = blogService.query()
                .select("id", "shop_id", "user_id", "title", "images", "liked", "comments", "create_time")
                .eq("user_id", id)
                .orderByDesc("create_time", "id")
                .page(new Page<>(SystemConstants.safeCurrentPage(currrent), SystemConstants.MAX_PAGE_SIZE));

        //获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset",defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max,offset);

    }
}
