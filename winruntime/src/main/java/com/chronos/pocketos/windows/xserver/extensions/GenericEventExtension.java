package com.chronos.pocketos.windows.xserver.extensions;

import static com.chronos.pocketos.windows.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.chronos.pocketos.windows.xconnector.XInputStream;
import com.chronos.pocketos.windows.xconnector.XOutputStream;
import com.chronos.pocketos.windows.xconnector.XStreamLock;
import com.chronos.pocketos.windows.xserver.XClient;
import com.chronos.pocketos.windows.xserver.XServer;
import com.chronos.pocketos.windows.xserver.errors.BadImplementation;
import com.chronos.pocketos.windows.xserver.errors.XRequestError;

import java.io.IOException;

public class GenericEventExtension extends Extension {
    public static final byte MAJOR_VERSION = 1;
    public static final byte MINOR_VERSION = 0;

    public GenericEventExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "Generic Event Extension";
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (opcode != 0) throw new BadImplementation();
        inputStream.skip(4);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort(MAJOR_VERSION);
            outputStream.writeShort(MINOR_VERSION);
            outputStream.writePad(20);
        }
    }
}
