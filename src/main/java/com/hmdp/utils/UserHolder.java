package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/*
Synchronized用于线程间的数据共享，而ThreadLocal则用于线程间的数据隔离。
Synchronized是利用锁的机制，使变量或代码块在某一时该只能被一个线程访问。
ThreadLocal为每一个线程都提供了变量的副本，使得每个线程在某一时间访问到的并不是同一个对象，这样就隔离了多个线程对数据的数据共享。
而Synchronized却正好相反，它用于在多个线程间通信时能够获得数据共享。
* */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
