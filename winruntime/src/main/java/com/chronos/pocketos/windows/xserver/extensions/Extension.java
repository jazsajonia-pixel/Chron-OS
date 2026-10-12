package com.chronos.pocketos.windows.xserver.extensions;

import com.chronos.pocketos.windows.xconnector.XInputStream;
import com.chronos.pocketos.windows.xconnector.XOutputStream;
import com.chronos.pocketos.windows.xserver.XClient;
import com.chronos.pocketos.windows.xserver.XServer;
import com.chronos.pocketos.windows.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class Extension {
    public static final byte START_MAJOR_OPCODE = -100;
    private final byte majorOpcode;
    protected final XServer xServer;
    private byte firstEventId;
    private byte firstErrorId;

    public Extension(XServer xServer, byte majorOpcode) {
        this.xServer = xServer;
        this.majorOpcode = majorOpcode;
    }

    public abstract String getName();

    public byte getMajorOpcode() {
        return majorOpcode;
    }

    public final byte getFirstEventId() {
        return firstEventId;
    }

    public final void setFirstEventId(byte firstEventId) {
        this.firstEventId = firstEventId;
    }

    public final byte getFirstErrorId() {
        return firstErrorId;
    }

    public final void setFirstErrorId(byte firstErrorId) {
        this.firstErrorId = firstErrorId;
    }

    public byte getEventCount() {
        return 0;
    }

    public byte getErrorCount() {
        return 0;
    }

    public abstract void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError;
}
