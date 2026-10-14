package com.dzdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dzdp.dto.Result;
import com.dzdp.dto.ScrollResult;
import com.dzdp.dto.UserDTO;
import com.dzdp.entity.Blog;
import com.dzdp.entity.Follow;
import com.dzdp.entity.User;
import com.dzdp.mapper.BlogMapper;
import com.dzdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dzdp.service.IFollowService;
import com.dzdp.service.IUserService;
import com.dzdp.utils.SystemConstants;
import com.dzdp.utils.SortUtils;
import com.dzdp.utils.UserHolder;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dzdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.dzdp.utils.RedisConstants.FEED_KEY;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    /** 推模式写 Feed 时每批处理的粉丝数，避免大 V 一次把几十万粉丝全部拉进内存 */
    private static final int FEED_BATCH_SIZE = 500;

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询：列表页不需要 content（单列 2048），显式指定列
        Page<Blog> page = query()
                .select("id", "shop_id", "user_id", "title", "images", "liked", "comments", "create_time")
                .orderByDesc("liked", "id")
                .page(new Page<>(SystemConstants.safeCurrentPage(current), SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 批量填充：原实现对每条记录查两次，一页 10 条就是 20 次查询（N+1）
        fillBlogUser(records);
        fillBlogLiked(records);
        return Result.ok(records);
    }

    @Override
    public Result updateLike(Long id){
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前用户有没有点赞
        String key=BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null) {
            //3.如果未点赞，可以点赞
            //3.1.数据库点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //3.2.保存用户到redis的set集合  zadd key value score
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4.如果已经点赞，取消点赞
            //4.1.数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if(isSuccess) {
                //4.2.将用户从set集合中移除
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询top5的点赞用户
        String key=BLOG_LIKED_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //3.根据用户id查询用户（listByIds 走主键 in 查询）
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //4.按 Redis 返回的顺序（点赞时间倒序）在内存里重排，
        //  替代原来的 order by field(id,...)：既不需要拼接 SQL，也不会产生 filesort
        return Result.ok(SortUtils.sortByIds(userDTOS, ids, UserDTO::getId));
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
           return Result.fail("新增笔记失败");
        }
        // 推送笔记id给所有粉丝，两处优化：
        // 1) 用 id 游标分批查，而不是一次 list() 拉全量粉丝，避免大 V 有几十万粉丝时把内存撑爆；
        // 2) 每批用 pipeline 一次性发完，把 N 次网络往返压成 1 次（原来是逐个 ZADD）。
        String blogId = blog.getId().toString();
        long lastFollowId = 0L;
        while (true) {
            List<Follow> follows = followService.query()
                    .eq("follow_user_id", user.getId())
                    .gt("id", lastFollowId)
                    .orderByAsc("id")
                    .last("limit " + FEED_BATCH_SIZE)
                    .list();
            if (follows.isEmpty()) {
                break;
            }
            long now = System.currentTimeMillis();
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] member = blogId.getBytes(StandardCharsets.UTF_8);
                for (Follow follow : follows) {
                    byte[] feedKey = (FEED_KEY + follow.getUserId()).getBytes(StandardCharsets.UTF_8);
                    connection.zAdd(feedKey, now, member);
                }
                return null;
            });
            // 游标推进：以本批最大的 follow.id 作为下一批的起点
            lastFollowId = follows.get(follows.size() - 1).getId();
            if (follows.size() < FEED_BATCH_SIZE) {
                break;
            }
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱  zrevrangebyscore key  min max limit offset count
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析数据：blogId,minTime时间戳),offset
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //3.1.获取id
            ids.add(Long.valueOf( typedTuple.getValue()));
            //3.2.获取分数（时间戳）
            long time=typedTuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }

        }
        //4.根据id查询blog：主键 in 查询 + 内存重排，替代 order by field(id,...)
        List<Blog> blogs = SortUtils.sortByIds(listByIds(ids), ids, Blog::getId);
        //4.1 批量填充作者与点赞状态，避免逐条查询
        fillBlogUser(blogs);
        fillBlogLiked(blogs);
        //5.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);

    }

    @Override
    public Result  queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        //2.查询blog关联的对象
        if(blog==null){
            return Result.fail("博客不存在");
        }
        // 单条也复用批量逻辑：1 次 IN 查询 + 1 次 pipeline，与原来的两次单查等价
        List<Blog> blogs = Collections.singletonList(blog);
        fillBlogUser(blogs);
        //3.查询blog是否点赞
        fillBlogLiked(blogs);
        return Result.ok(blog);
    }

    /**
     * 批量填充作者信息：一次 IN 查询替代 N 次 getById，解决列表接口的 N+1 问题。
     */
    private void fillBlogUser(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        Set<Long> userIds = blogs.stream().map(Blog::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>(Math.max(userIds.size() * 2, 4));
        for (User user : userService.listByIds(userIds)) {
            userMap.put(user.getId(), user);
        }
        for (Blog blog : blogs) {
            User user = userMap.get(blog.getUserId());
            if (user != null) {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            }
        }
    }

    /**
     * 批量判断"当前登录用户有没有点赞过"：用 pipeline 把 N 次 ZSCORE 压成 1 次网络往返。
     * 未登录时直接跳过，不需要查 Redis。
     */
    private void fillBlogLiked(List<Blog> blogs) {
        UserDTO user = UserHolder.getUser();
        if (user == null || blogs == null || blogs.isEmpty()) {
            return;
        }
        byte[] member = user.getId().toString().getBytes(StandardCharsets.UTF_8);
        List<Object> scores = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Blog blog : blogs) {
                byte[] key = (BLOG_LIKED_KEY + blog.getId()).getBytes(StandardCharsets.UTF_8);
                connection.zScore(key, member);
            }
            return null;
        });
        for (int i = 0; i < blogs.size(); i++) {
            // ZSCORE 返回 null 表示没点过赞；返回值类型（Double/String）不影响这里的判断
            blogs.get(i).setIsLike(scores.get(i) != null);
        }
    }

}
