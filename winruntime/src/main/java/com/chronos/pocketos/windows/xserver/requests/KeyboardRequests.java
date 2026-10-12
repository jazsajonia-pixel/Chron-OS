package com.chronos.pocketos.windows.xserver.requests;

import static com.chronos.pocketos.windows.xserver.Keyboard.KEYSYMS_PER_KEYCODE;
import static com.chronos.pocketos.windows.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.chronos.pocketos.windows.xconnector.XInputStream;
import com.chronos.pocketos.windows.xconnector.XOutputStream;
import com.chronos.pocketos.windows.xconnector.XStreamLock;
import com.chronos.pocketos.windows.xserver.Keyboard;
import com.chronos.pocketos.windows.xserver.XClient;
import com.chronos.pocketos.windows.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class KeyboardRequests {
    public static void getKeyboardMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        byte firstKeycode = inputStream.readByte();
        int count = inputStream.readUnsignedByte();
        inputStream.skip(2);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(KEYSYMS_PER_KEYCODE);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(count);
            outputStream.writePad(24);

            int i = firstKeycode - Keyboard.MIN_KEYCODE;
            while (count != 0) {
                outputStream.writeInt(client.xServer.keyboard.keysyms[i]);
                count--;
                i++;
            }
        }
    }

    public static void getModifierMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(2);
            outputStream.writePad(24);
            outputStream.writePad(8);
        }
    }
}