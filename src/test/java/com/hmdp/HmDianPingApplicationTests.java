package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Test
    void test() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("123");
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now());
        String jsonStr = JSONUtil.toJsonStr(redisData);

        System.out.println(jsonStr);
    }

}
