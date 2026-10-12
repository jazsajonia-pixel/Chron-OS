package com.chronos.pocketos.windows.xserver.events;

import com.chronos.pocketos.windows.xconnector.XOutputStream;
import com.chronos.pocketos.windows.xconnector.XStreamLock;
import com.chronos.pocketos.windows.xserver.extensions.XInputExtension;

import java.io.IOException;

public class XIRawMotionNotify extends Event {
    public static final byte RAW_MOTION = 17;
    private final XInputExtension xInputExtension;
    private final short deviceId;
    private final float[] valuators;
    private final int valuatorMask;

    public XIRawMotionNotify(XInputExtension xInputExtension, short deviceId, float[] valuators, int valuatorMask) {
        super(GENERIC_EVENT_ID);
        this.xInputExtension = xInputExtension;
        this.deviceId = deviceId;
        this.valuators = valuators;
        this.valuatorMask = valuatorMask;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            int replyLength = (4 + valuators.length * 8 * 2) / 4;

            outputStream.writeByte(code);
            outputStream.writeByte(xInputExtension.getMajorOpcode());
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(replyLength);
            outputStream.writeShort(RAW_MOTION);
            outputStream.writeShort(deviceId);
            outputStream.writeInt((int)System.currentTimeMillis());
            outputStream.writeInt(0);
            outputStream.writeShort(deviceId);
            outputStream.writeShort((short)1);
            outputStream.writeInt(0);
            outputStream.writePad(4);
            outputStream.writeInt(valuatorMask);

            for (float value : valuators) outputStream.writeFP3232(value);
            for (float value : valuators) outputStream.writeFP3232(value);
        }
    }
}
