package com.dzdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

// proxyTargetClass = true：Controller 没有接口，且项目里存在按实现类注入的场景
// （如 FollowServiceImpl 注入 UserServiceImpl），统一使用 CGLIB 代理可同时满足两者
@EnableAspectJAutoProxy(exposeProxy = true, proxyTargetClass = true)
@MapperScan("com.dzdp.mapper")
@EnableRabbit
@SpringBootApplication
public class dzdpApplication {
    //
    public static void main(String[] args) {
        SpringApplication.run(dzdpApplication.class, args);
    }

}
