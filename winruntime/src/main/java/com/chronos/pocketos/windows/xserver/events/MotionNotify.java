package com.chronos.pocketos.windows.xserver.events;

import com.chronos.pocketos.windows.core.Bitmask;
import com.chronos.pocketos.windows.xserver.Window;

public class MotionNotify extends InputDeviceEvent {
    public MotionNotify(boolean detail, Window root, Window event, Window child, short rootX, short rootY, short eventX, short eventY, Bitmask state) {
        super(6, (byte)(detail ? 1 : 0), root, event, child, rootX, rootY, eventX, eventY, state);
    }
}
