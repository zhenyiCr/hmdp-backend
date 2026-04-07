package com.hmdp.service.impl;

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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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

    @Override
    @Transactional
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

        /*boolean isLock = simpleRedisLock.tryLock(5);
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
        }*/


//        synchronized (Userid.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
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
