package com.chronos.pocketos.windows.alsaserver;

import com.chronos.pocketos.windows.xconnector.ConnectedClient;
import com.chronos.pocketos.windows.xconnector.ConnectionHandler;

public class ALSAClientConnectionHandler implements ConnectionHandler {
    private final ALSAClient.Options options;

    public ALSAClientConnectionHandler(ALSAClient.Options options) {
        this.options = options;
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {
        client.setTag(new ALSAClient(options));
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {
        if (client.getTag() != null) ((ALSAClient)client.getTag()).release();
    }
}
