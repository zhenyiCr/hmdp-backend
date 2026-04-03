package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.xml.crypto.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }

        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /*    public Shop queryWithPassThrough (Long id) {
        // 1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            return null;
        }
        // 4. 不存在 从数据库里查找 并写入缓存
        Shop shop = getById(id);
        if (BeanUtil.isEmpty(shop)) {
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5. 写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }*/

/*    public Shop queryWithMutex (Long id) {
        // 1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            return null;
        }
        // 4. 尝试获取互斥锁
        // 4.1 判断是否获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            if (!tryLock(lockKey)) {
                // 4.2 失败 休眠一段时间
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.3 成功 查询数据库
            // 5. 不存在 从数据库里查找 并写入缓存
            shop = getById(id);
            if (BeanUtil.isEmpty(shop)) {
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6. 写入缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unLock(lockKey);
        }
        return shop;
    }*/

/*    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire (Long id) {
        // 1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.不存在直接返回
            return null;
        }
        // 存在 判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop bean = BeanUtil.toBean(redisData.getData(), Shop.class);
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期
            return bean;
        }
        // 过期 获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            // 失败
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id,1800L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 成功

        return bean;
    }

    public void saveShop2Redis (Long id,Long expireSeconds) {
        // 查询店铺数据
        Shop shop = getById(id);
        // 封装成逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 写出redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }*/

    /*    private boolean tryLock (String key) {
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }
    private void unLock (String key) {
        stringRedisTemplate.delete(key);
    }*/




}
