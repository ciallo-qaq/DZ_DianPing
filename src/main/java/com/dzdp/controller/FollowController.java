package com.dzdp.controller;


import com.dzdp.dto.Result;
import com.dzdp.service.IFollowService;
import com.dzdp.trace.ApiTrace;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;


@ApiTrace
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId,@PathVariable("isFollow") Boolean isFollow){
        return followService.follow(followUserId,isFollow);
    }


    @GetMapping("/or/not/{id}")
    public Result follow(@PathVariable("id") Long followUserId){
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable Long id){
        return followService.followCommons(id);
    }
}
