package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
//    由于未来业务有不同种类，所以锁也应该不同，所以就可以给锁加个前缀以区分不同的业务锁
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long>  UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean tryLock(Long timeoutSec) {
//        获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        获取锁

        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
//        防止拆箱操作出现异常
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unLock() {
//        调用Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }

/**
 *     @Override
 *     public void unLock() {
 * //        防止多线程出现某一线程释放其他线程锁的情况，所以在释放锁之前根据线程标识进行判断
 * //        获取线程标识
 *         String threadId = ID_PREFIX + Thread.currentThread().getId();
 * //        获取锁中的标识
 *         String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
 * //        判断标识是否一致
 *         if (threadId.equals(id)){
 * //            释放锁
 *             stringRedisTemplate.delete(KEY_PREFIX + name);
 *         }
 *     }
 */
}
