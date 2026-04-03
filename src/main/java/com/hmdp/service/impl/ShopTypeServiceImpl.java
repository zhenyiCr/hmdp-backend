package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> list() {
        // 1. 构造 Redis key
        String key = CACHE_SHOP_TYPE_KEY;

        // 2. 先从 Redis 查全部店铺类型
        List<String> typeList = stringRedisTemplate.opsForList().range(key, 0, -1);

        if (CollUtil.isNotEmpty(typeList)) {
            // 3. 缓存存在 → 直接转成 List 返回
            return typeList.stream().map(Json -> JSONUtil.toBean(Json, ShopType.class)).collect(Collectors.toList());
        }
        // 4. 缓存不存在
        List<ShopType> list = query().orderByAsc("sort").list();
        List<String> collect = list.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        // 存入 Redis
        stringRedisTemplate.opsForList().rightPushAll(key, collect);
        return list;
    }
}
