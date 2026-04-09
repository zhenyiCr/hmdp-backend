-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.秒杀
local orderId = ARGV[3]

-- 2.获取key
local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足
    return 1
end

-- 3.2.判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已下单
    return 2
end

-- 扣库存
redis.call('incrby', stockKey, -1)
-- 下单 (保存用户)
redis.call('sadd', orderKey, userId)
-- 发送消息到队列中 XADD stream.orders * k1 v1 k2 v2
redis.call('xadd', 'stream.orders', '*', 'id', orderId, 'userId', userId, 'voucherId', voucherId)
return 0