package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.apache.tomcat.jni.Time;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        // 判断登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        String key = "follow:"+userId;
        if (Boolean.FALSE.equals(isFollow)) {
            // 取消关注
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            // 取消关注后，更新缓存
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
            return Result.ok();
        }
        // 关注
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(followUserId);
        follow.setCreateTime(LocalDateTime.now());
        boolean isSuccess = save(follow);
        // 关注成功后，更新缓存
        if (isSuccess) {
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 判断登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        // 查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        String key1 = "follow:"+userId;
        String key2 = "follow:"+id;
        Set<String> set = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (set == null || set.isEmpty()) {
            return Result.ok();
        }
        Set<Long> collect = set.stream().map(Long::valueOf).collect(Collectors.toSet());
        List<User> followUser = userService.listByIds(collect);
        List<UserDTO> list = followUser.stream().map(u -> BeanUtil.copyProperties(u, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(list);
    }


}
