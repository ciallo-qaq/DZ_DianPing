package com.dzdp.config;

import javax.annotation.Resource;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Resource
    private RedisProperties redisProperties;

    @Bean
    public RedissonClient redissonClient() {

        Config config = new Config();

        config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());

        String password = redisProperties.getPassword();
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }

        return Redisson.create(config);
    }
}