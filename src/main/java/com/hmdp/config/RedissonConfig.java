package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
//        配置
        Config config = new Config();
//        集群使用config.useClusterServers()配置多个Redis地址
        config.useSingleServer().setAddress("redis://192.168.209.128");
//        创建Redisson对象
        return Redisson.create(config);
    }
}
