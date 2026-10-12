package com.chronos.pocketos.windows.xenvironment.components;

import com.chronos.pocketos.windows.alsaserver.ALSAClient;
import com.chronos.pocketos.windows.alsaserver.ALSAClientConnectionHandler;
import com.chronos.pocketos.windows.alsaserver.ALSARequestHandler;
import com.chronos.pocketos.windows.xconnector.UnixSocketConfig;
import com.chronos.pocketos.windows.xconnector.XConnectorEpoll;
import com.chronos.pocketos.windows.xenvironment.EnvironmentComponent;

public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;
    private final ALSAClient.Options options;

    public ALSAServerComponent(UnixSocketConfig socketConfig, ALSAClient.Options options) {
        this.socketConfig = socketConfig;
        this.options = options;
    }

    @Override
    public void start() {
        if (connector != null) return;
        ALSAClient.assignFramesPerBuffer(environment.getContext());
        connector = new XConnectorEpoll(socketConfig, new ALSAClientConnectionHandler(options), new ALSARequestHandler());
        connector.setMultithreadedClients(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
    }
}
