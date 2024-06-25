package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryBlogById(Long id) {
//        1.查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("博客不存在!");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
//        查询是否被点赞
        isBlogLiked(blog);
        return Result.ok();
    }

    private void isBlogLiked(Blog blog) {
//        获取登录用户
        Long userId = UserHolder.getUser().getId();
//        判断是否已点赞
        String key = "blog:liked:"+blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }

    @Override
    public Result likeBlog(Long id) {
//        获取登录用户
        Long userId = UserHolder.getUser().getId();
//        判断是否已点赞
        String key = "blog:liked:"+id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isMember)){
//        如果未点赞，可以点赞
//        数据库点赞数+1
            boolean isSucess = update().setSql("liked = liked + 1").eq("id", id).update();
//        保存用户到Redis的set集合
            if (isSucess){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }
        } else {
//        如果已点赞
//        数据库点赞数-1
       boolean isSucess = update().setSql("liked = liked - 1").eq("id", id).update();
//       将用户数据移除
            if (isSucess){
                stringRedisTemplate.opsForSet().remove(key,userId.toString());
            }
        }
        return null;
    }
}
