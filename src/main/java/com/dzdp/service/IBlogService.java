package com.dzdp.service;

import com.dzdp.dto.Result;
import com.dzdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    Result queryHotBlog(Integer current);

    Result updateLike(Long id);

    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}
