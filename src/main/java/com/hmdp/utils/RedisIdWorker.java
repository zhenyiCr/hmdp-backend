package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    // 开始时间戳
    private static final long BEGIN_TIMESTAMP = 1767225600L;


    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 序列号的位数
    private static final int COUNT_BITS = 32;

    public long nextId (String keyPrefix) {
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowEpochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowEpochSecond - BEGIN_TIMESTAMP;

        // 生成序列号
        // 获取当天日期 精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 排序并返回    按二进制位拼接 以十进制存储
        return timestamp << COUNT_BITS | count;
    }




/*    public static void main(String[] args) {
        LocalDateTime data = LocalDateTime.of(2026,1,1,0,0,0);
        long epochSecond = data.toEpochSecond(ZoneOffset.UTC);
        System.out.println(epochSecond);
    }*/
}
