package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
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

    @Override
    public Result queryById(Long id) {
//        解决缓存穿透的代码
//        Shop shop = queryWithPassThrough(id);
//        用互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null){
            return Result.fail("店铺信息不存在!");
        }

        return Result.ok(shop);
    }

//    将原有的查询商铺信息的代码进行封装
//        解决缓存穿透的代码
    public Shop queryWithPassThrough(Long id){
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
    }

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

    public Shop queryWithLogicalExpire(Long id){
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
    }

//    添加互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        由于返回的是对象型，不是基本类型，如果直接返回的话会发生拆包可能会出现空指针，所以使用工具类进行处理
        return BooleanUtil.isTrue(flag);
    }
//    删除锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds){
//        1. 查看店铺数据
        Shop shop = getById(id);
//        2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        3. 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }
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
