package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


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
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
//    注入Redisson锁
    @Resource
    private RedissonClient redissonClient;

//    创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

//    创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

//    该注解的作用是当前类初始化完成后执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
    }
//    执行Lua脚本
    private static final DefaultRedisScript<Long>  SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
//    创建线程任务
    private class VoucherOrderHandle implements Runnable{
    @Override
    public void run() {
        while (true){
            try {
//             获取队列中的订单信息
                VoucherOrder voucherOrder = orderTasks.take();
//                创建订单
             handleVoucherOrder(voucherOrder);
            } catch (Exception e) {
                log.error("处理秒杀券订单异常",e);
            }
        }
    }
}

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
//        1. 获取用户
        Long userId = voucherOrder.getUserId();
        //        2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        3.获取锁
        boolean isLock = lock.tryLock();
//        4.判断尝试获取锁成功
        if (isLock){
//            获取锁失败，返回错误或重试
            log.error("不允许重复下单!");
            return;
        }
        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally {
//            释放锁
            lock.unlock();
        }

    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
//        获取用户
        Long userId = UserHolder.getUser().getId();
//         执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
//        判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            //        不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
//        为0，有购买资格,把下单信息保存到阻塞队列中
        long orderId = redisIdWorker.nextId("order");
        //        创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
//        用户id
        voucherOrder.setUserId(userId);
//       代金券id
        voucherOrder.setVoucherId(voucherId);
//        放入阻塞队列
        orderTasks.add(voucherOrder);

//        获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        返回订单id
        return Result.ok(0);
    }
/*
    @Override
    public Result seckillVoucher(Long voucherId) {
//        1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            尚未开始
            return Result.fail("秒杀还未开始!");
        }
//        3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束!");
        }
//        4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足!");
        }

//        添加悲观锁
//        由于事务是方法执行完再执行，所以可能出现释放锁后数据库还没更新数据但是这时候一个线程来创建用户订单，这样会出现线程安全问题
//        所以就需要在整个事务上加锁
        Long userId = UserHolder.getUser().getId();
//        这里需要保证userid是同一个的加锁，但是toString每次都会new一个对象，所以需要加intern返回对象的规范引用，即去底层找userid值是否相同

//        创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        获取锁
        boolean isLock = lock.tryLock();
//        判断尝试获取锁成功
        if (isLock){
//            获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单!");
        }
        try{
//            由于@Transactional是spring来进行事务管理的，所以就需要使用spring可以管理的对象，而暂时创建的createVoucherOrde是没有被代理的
//            所以需要拿到spring的代理对象,他的代理对象其实就是该类的接口类
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
//            释放锁
            lock.unlock();
        }


    }
 */
    @Transactional
    public  Result createVoucherOrder(VoucherOrder voucherOrder){
        //        5.实现一人一单
        Long userId = voucherOrder.getUserId();

//        5.1 查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
//        5.2 判断是否存在用户已购买订单
            if (count > 0){
//            该用户已经购买
                log.error("用户已经购买过一次了!");
                return Result.fail("已购买，无法重复购买!");
            }
//        6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")  // set stock = stock - 1
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0) // where id = ? and stock > 0
                    .update();
            if (!success){
                return Result.fail("库存不足!");
            }
////        6.创建订单
//            VoucherOrder voucherOrder = new VoucherOrder();
////        6.1 订单id
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
////        6.2 用户id
//            voucherOrder.setUserId(userId);
////        6.3 代金券id
//            voucherOrder.setVoucherId(voucherOrder);
//        保存订单
            save(voucherOrder);
            //        7.返回订单id
            return Result.ok();
    }
}
