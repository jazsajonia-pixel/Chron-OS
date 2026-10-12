package com.chronos.pocketos.windows.xenvironment.components;

import com.chronos.pocketos.windows.xenvironment.EnvironmentComponent;
import com.chronos.pocketos.windows.xconnector.XConnectorEpoll;
import com.chronos.pocketos.windows.xconnector.UnixSocketConfig;
import com.chronos.pocketos.windows.xserver.XClientConnectionHandler;
import com.chronos.pocketos.windows.xserver.XClientRequestHandler;
import com.chronos.pocketos.windows.xserver.XServer;

public class XServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;

    public XServerComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, new XClientConnectionHandler(xServer), new XClientRequestHandler());
        connector.setInitialInputBufferCapacity(4096);
        connector.setInitialOutputBufferCapacity(4096);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
    }

    public XServer getXServer() {
        return xServer;
    }
}
