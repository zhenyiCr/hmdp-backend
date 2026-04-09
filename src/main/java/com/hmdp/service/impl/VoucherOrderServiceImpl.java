package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;



    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init () {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class  VoucherOrderHandler implements Runnable {
        private final String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofMillis(2000)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 失败 没有消息 继续循环
                        continue;
                    }
                    // 处理消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    // 解析消息内容
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value,new VoucherOrder(),true);
                    // 成功 消息内容为订单信息
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("获取订单信息失败", e);
                    handlePendingList();
                }

            }
        }
        private void handlePendingList() {
            while (true) {
                try {
                    // 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAM stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 失败 pending-list没有消息 没有异常消息 跳出循环
                        break;
                    }
                    // 处理消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    // 解析消息内容
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value,new VoucherOrder(),true);
                    // 成功 消息内容为订单信息
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("获取订单信息失败", e);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }

            }
        }

    }

/*    private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024 * 1024);
    private class  VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("获取订单信息失败", e);
                }

            }
        }

    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 加锁
        String keyName = "lock:order:" + voucherOrder.getUserId();
        RLock lock = redissonClient.getLock(keyName);
        try {
            if (!lock.tryLock(1,20, TimeUnit.SECONDS)) {
                log.error("不允许重复下单，请稍后再试");
                return;
            }
            proxy.createVoucherOrder(voucherOrder);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 静态代码块：类加载时就初始化，只执行一次，性能最高
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 指定Lua脚本文件路径：resources目录下的 seckill.lua
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 设置Lua脚本返回值类型：Long
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        LocalDateTime now = LocalDateTime.now();
        // 查询优惠卷
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        // 判断优惠卷是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        if (beginTime.isAfter(now)) {
            return Result.fail("优惠卷未开始");
        }
        // 判断优惠卷是否结束
        LocalDateTime endTime = voucher.getEndTime();
        if (endTime.isBefore(now)) {
            return Result.fail("优惠卷已过期");
        }

        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order:");
        // 执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail( r == 1 ? "库存不足" : "用户已下单" );
        }
        // 获取当前代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

/*
    @Override
    public Result seckillVoucher(Long voucherId) {
        LocalDateTime now = LocalDateTime.now();
        // 查询优惠卷
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        // 判断优惠卷是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        if (beginTime.isAfter(now)) {
            return Result.fail("优惠卷未开始");
        }
        // 判断优惠卷是否结束
        LocalDateTime endTime = voucher.getEndTime();
        if (endTime.isBefore(now)) {
            return Result.fail("优惠卷已过期");
        }

        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail( r == 1 ? "库存不足" : "用户已下单" );
        }
        // 有购买资格 下单信息保存在阻塞队列
        Long orderId = redisIdWorker.nextId("order:");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        // 获取当前代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
*/

/*    @Override
    public Result seckillVoucher(Long voucherId) {
        LocalDateTime now = LocalDateTime.now();
        // 查询优惠卷
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        // 判断优惠卷是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        if (beginTime.isAfter(now)) {
            return Result.fail("优惠卷未开始");
        }
        // 判断优惠卷是否结束
        LocalDateTime endTime = voucher.getEndTime();
        if (endTime.isBefore(now)) {
            return Result.fail("优惠卷已过期");
        }
        // 库存是否充足
        int stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }
        Long Userid = UserHolder.getUser().getId();

        // 加锁
        String keyName = "lock:order:" + Userid;
        RLock lock = redissonClient.getLock(keyName);
        try {
            if (!lock.tryLock(1,20, TimeUnit.SECONDS)) {
                return Result.fail("请稍后再试");
            }
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

        *//*boolean isLock = simpleRedisLock.tryLock(5);
        if (!isLock) {
            return Result.fail("请稍后再试");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
                simpleRedisLock.unlock();
        }*//*


//        synchronized (Userid.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long Userid = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", Userid).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("已经购买过该优惠卷");
        }
        // 扣减库存
        iSeckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0)
                .update();
        save(voucherOrder);
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单
        Long Userid = UserHolder.getUser().getId();
        int count = query().eq("user_id", Userid).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("已经购买过该优惠卷");
        }
        // 扣减库存
        iSeckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0)
                .update();

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(Userid);
        save(voucherOrder);
        return Result.ok(orderId);
    }

}
