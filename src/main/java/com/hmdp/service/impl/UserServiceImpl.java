package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Log4j2
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
//        1.校验电话号
        if (RegexUtils.isPhoneInvalid(phone)){
//        2.如果不符合返回错误信息
            return Result.fail("手机号格式错误!");
        }
//        3.符合，返回验证码,使用糊涂工具箱生成6为随机数
        String code = RandomUtil.randomNumbers(6);
//        4.保存验证码到 redis中 // set key value ex 120
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);
//        5.发送验证码
        log.debug("发送验证码成功:"+code);
//        返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        检验手机号
        String phone = loginForm.getPhone();
//        1.校验电话号
        if (RegexUtils.isPhoneInvalid(phone)){
//        2.如果不符合返回错误信息
            return Result.fail("手机号格式错误!");
        }
//        校验验证码,从redis中获取
        String  cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if (cachecode == null  || !cachecode.equals(code)){
            //        不一致,直接报错
            return Result.fail("验证码错误!");
        }
//        一致，根据手机号查询用户是否存在
        User user = query().eq("phone", phone).one();
//          判断用户是否存在
        if (user == null){
            //        不存在，创建新用户并保存
           user = createUserWithPhone(phone);
        }
//        保存用户信息到session,因为创建过新用户后也要保存信息到session中,
//        由于用户信息中有很多敏感信息，所以存入session中的信息不能太全面
//        保存用户信息到redis中
//        随机生成token
        String token = UUID.randomUUID().toString(true);
//        使用hutool工具箱拷贝对象属性,将user对象转化为HashKMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
//        存入redis中
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
//        设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
//        创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(8));
//        保存用户
        save(user);
        return user;
    }
}
