package com.chronos.pocketos.windows.xserver.extensions;

import static com.chronos.pocketos.windows.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.chronos.pocketos.windows.xconnector.XInputStream;
import com.chronos.pocketos.windows.xconnector.XOutputStream;
import com.chronos.pocketos.windows.xconnector.XStreamLock;
import com.chronos.pocketos.windows.xserver.XClient;
import com.chronos.pocketos.windows.xserver.XServer;

import java.io.IOException;

public class BigReqExtension extends Extension {
    private static final int MAX_REQUEST_LENGTH = 4194303;

    public BigReqExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "BIG-REQUESTS";
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(MAX_REQUEST_LENGTH);
            outputStream.writePad(20);
        }
    }
}
