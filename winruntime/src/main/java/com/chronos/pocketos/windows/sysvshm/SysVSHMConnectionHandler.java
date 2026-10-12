package com.chronos.pocketos.windows.sysvshm;

import com.chronos.pocketos.windows.xconnector.ConnectedClient;
import com.chronos.pocketos.windows.xconnector.ConnectionHandler;

public class SysVSHMConnectionHandler implements ConnectionHandler {
    private final SysVSharedMemory sysVSharedMemory;

    public SysVSHMConnectionHandler(SysVSharedMemory sysVSharedMemory) {
        this.sysVSharedMemory = sysVSharedMemory;
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {
        client.setTag(sysVSharedMemory);
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {}
}
