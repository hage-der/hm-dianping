package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {
//    由于LoginInterceptor这个类是我们自己创建的，所以spring并不知道这个类
//    所以使用Resource和Autowired无效,就需要我们自己创建构造器,同时在使用这个类的spring类中进行注入
//    在MvcConfig中注入
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        相对于之前的版本来说，RefreshTokenInterceptor已经将大部分的工作做完了，所以在这里只需要判断是否有登录状态即可
//        即(TreadLocal)中是否有用户
        if (UserHolder.getUser() == null){
//            没有用户，拦截，并设置状态码
            response.setStatus(401);
//             拦截
            return false;
        }
//        用户则放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        移除用户
        UserHolder.removeUser();
    }
}
