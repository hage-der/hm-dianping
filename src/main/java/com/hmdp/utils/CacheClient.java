package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Log4j2
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
//        设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
//        写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //     解决缓存穿透的代码
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //        1.从redis中查询商铺id
        String json = stringRedisTemplate.opsForValue().get(key);
//        2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //        3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
//        为防止缓存穿透，当命中后查看是否为空值
        if (json != null){
//            返回错误信息
            return null;
        }
//        4.不存在，查询数据库
//        由于我们不知道用户具体的查询结果是什么样，只知道是泛型，所以就让用户传入Function，并根据其进行判断
//        Shop shop = getById(id);
        R r = dbFallback.apply(id);
//        5.数据库中不存在，错误
        if (r == null){
//            为防止redis缓存穿透问题，当查询的数据在数据库不存在时，将空值写入redis中，并返回false
//            需要使用用户传入的时间
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            返回错误信息
            return null;
        }
//        6.存在，写入redis
        this.set(key,r,time,unit);
//        返回
        return r;
    }

    //    逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //        1.从redis中查询商铺id
        String json = stringRedisTemplate.opsForValue().get(key);
//        2. 判断是否存在
        if (StrUtil.isBlank(json)) {
//           3. 未命中，返回null
            return null;
        }
//        4. 命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
//        但实际上这个Object的本质是JsonObject,所以可以直接强转
//        Object data = redisData.getData();
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
//        5. 判断是否过期
//        过期时间是否在当前时间之后
        if (expireTime.isAfter(LocalDateTime.now())) {
            //        5.1 未过期，直接返回店铺信息
            return r;
        }
//        5.2  已过期，需要缓存重建
//        6. 缓存重建
//        6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
//        6.2 是否获取锁成功
        if (isLock){
//        6.3 成功，开启独立线程，进行缓存重建 （无论成功还是失败都需要返回）

//            再次查看是否逻辑过期
            json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            redisData = JSONUtil.toBean(json, RedisData.class);
            data = (JSONObject) redisData.getData();
            r = JSONUtil.toBean(data, type);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {
                //未过期，直接返回店铺信息
                return r;
            }

            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
//                    查询数据库
                    R r1 = dbFallback.apply(id);
//                    写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //                释放锁
                    unLock(lockKey);
                }
            });
        }
//        6.4 返回过期的店铺信息
        return r;
    }

    //    创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //    创建锁
    private boolean tryLock(String key){
//        使用的是setnx命令，即如果key存在则创建失败，也修改失败，如果不存在则创建成功
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        由于返回的是对象型，不是基本类型，如果直接返回的话会发生拆包可能会出现空指针，所以使用工具类进行处理
        return BooleanUtil.isTrue(flag);
    }
    //    删除锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
