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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
//    注入Redisson锁
    @Resource
    private RedissonClient redissonClient;
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

    @Transactional
    public  Result createVoucherOrder(Long voucherId){
        //        5.实现一人一单
        Long userId = UserHolder.getUser().getId();

//        5.1 查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        5.2 判断是否存在用户已购买订单
            if (count > 0){
//            该用户已经购买
                return Result.fail("已购买，无法重复购买!");
            }
//        6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")  // set stock = stock - 1
                    .eq("voucher_id",voucherId).gt("stock",0) // where id = ? and stock > 0
                    .update();
            if (!success){
                return Result.fail("库存不足!");
            }
//        6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
//        6.1 订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
//        6.2 用户id
            voucherOrder.setUserId(userId);
//        6.3 代金券id
            voucherOrder.setVoucherId(voucherId);
//        保存订单
            save(voucherOrder);
            //        7.返回订单id
            return createVoucherOrder(orderId);
    }
}
