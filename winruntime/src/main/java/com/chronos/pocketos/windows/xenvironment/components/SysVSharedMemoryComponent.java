package com.chronos.pocketos.windows.xenvironment.components;

import com.chronos.pocketos.windows.sysvshm.SysVSHMConnectionHandler;
import com.chronos.pocketos.windows.sysvshm.SysVSHMRequestHandler;
import com.chronos.pocketos.windows.sysvshm.SysVSharedMemory;
import com.chronos.pocketos.windows.xconnector.UnixSocketConfig;
import com.chronos.pocketos.windows.xconnector.XConnectorEpoll;
import com.chronos.pocketos.windows.xenvironment.EnvironmentComponent;
import com.chronos.pocketos.windows.xserver.SHMSegmentManager;
import com.chronos.pocketos.windows.xserver.XServer;

public class SysVSharedMemoryComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    public final UnixSocketConfig socketConfig;
    private SysVSharedMemory sysVSharedMemory;
    private final XServer xServer;

    public SysVSharedMemoryComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        sysVSharedMemory = new SysVSharedMemory();
        connector = new XConnectorEpoll(socketConfig, new SysVSHMConnectionHandler(sysVSharedMemory), new SysVSHMRequestHandler());
        connector.start();

        xServer.setSHMSegmentManager(new SHMSegmentManager(sysVSharedMemory));
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }

        sysVSharedMemory.deleteAll();
    }
}
