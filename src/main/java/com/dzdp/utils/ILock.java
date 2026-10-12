package com.dzdp.utils;

public interface ILock {

     boolean tryLock(long timeoutSec);

    void delLock();
}
