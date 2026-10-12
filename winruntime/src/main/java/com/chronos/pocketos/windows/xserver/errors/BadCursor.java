package com.chronos.pocketos.windows.xserver.errors;

public class BadCursor extends XRequestError {
    public BadCursor(int data) {
        super(6, data);
    }
}
