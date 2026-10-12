package com.chronos.pocketos.windows.winhandler;

public interface OnGetProcessInfoListener {
    void onGetProcessInfo(int index, int count, ProcessInfo processInfo);
}
