package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
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
//        解决缓存穿透的代码
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

//        用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

//        利用逻辑过期来解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null){
            return Result.fail("店铺信息不存在!");
        }

        return Result.ok(shop);
    }

//    将原有的查询商铺信息的代码进行封装
//        解决缓存穿透的代码
    /*public Shop queryWithPassThrough(Long id){
        //        1.从redis中查询商铺id
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //        3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
//        为防止缓存穿透，当命中后查看是否为空值
        if (shopJson != null){
//            返回错误信息
            return null;
        }
//        4.不存在，查询数据库
        Shop shop = getById(id);
//        5.数据库中不存在，错误
        if (shop == null){
//            为防止redis缓存穿透问题，当查询的数据在数据库不存在时，将空值写入redis中，并返回false
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
//        6.存在，写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        返回
        return shop;
    }*/

//    用互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){
        //        1.从redis中查询商铺id
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //        3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
//       3. 为防止缓存穿透，当命中后查看是否为空值
        if (shopJson != null){
//            返回错误信息
            return null;
        }
//        4.实现缓存重建
//        4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
//        4.2 判断是否获取成功
            if (!isLock){
                //        4.3 失败就重试并休眠
                Thread.sleep(50);
                return queryWithMutex(id);
            }
//        获取锁后再次查询redis中是否有值
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)) {
                //        3.存在，直接返回
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
//        4.4 成功，根据id查询数据库
            shop = getById(id);
//        5.数据库中不存在，错误
            if (shop == null){
    //            为防止redis缓存穿透问题，当查询的数据在数据库不存在时，将空值写入redis中，并返回错误信息
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
    //            返回错误信息
                return null;
            }
//        6.存在，写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //      7. 释放互斥锁
            unLock(lockKey);
        }
        //       8. 返回
        return shop;
    }

//    逻辑过期解决缓存击穿
/*    public Shop queryWithLogicalExpire(Long id){
        //        1.从redis中查询商铺id
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        2. 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
//           3. 未命中，返回null
            return null;
        }
//        4. 命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        但实际上这个Object的本质是JsonObject,所以可以直接强转
//        Object data = redisData.getData();
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
//        5. 判断是否过期
//        过期时间是否在当前时间之后
        if (expireTime.isAfter(LocalDateTime.now())) {
            //        5.1 未过期，直接返回店铺信息
            return shop;
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
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isBlank(shopJson)) {
                return null;
            }
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            data = (JSONObject) redisData.getData();
            shop = JSONUtil.toBean(data, Shop.class);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {
                //未过期，直接返回店铺信息
                return shop;
            }

            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
//                重建缓存
                    this.saveShop2Redis(id,30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //                释放锁
                    unLock(lockKey);
                }
            });
        }
//        6.4 返回过期的店铺信息
        return shop;
    }*/

//    创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

//    添加互斥锁
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

//    设置逻辑过期时间，已在CacheClient中封装为setWithLogicalExpire
/*    public void saveShop2Redis(Long id,Long expireSeconds){
//        1. 查看店铺数据
        Shop shop = getById(id);
//        2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        3. 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }*/
    @Override
    @Transactional
//    通过事务控制方法原子性，如果数据库更新失败就回滚
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空!");
        }
//        1.更新数据库
        updateById(shop);
//        2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
