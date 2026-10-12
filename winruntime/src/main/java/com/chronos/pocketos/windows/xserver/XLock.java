package com.chronos.pocketos.windows.xserver;

public interface XLock extends AutoCloseable {
    @Override
    void close();
}
