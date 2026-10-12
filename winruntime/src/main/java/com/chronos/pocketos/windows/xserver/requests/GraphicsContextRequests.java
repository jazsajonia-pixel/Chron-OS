package com.chronos.pocketos.windows.xserver.requests;

import com.chronos.pocketos.windows.xconnector.XInputStream;
import com.chronos.pocketos.windows.xconnector.XOutputStream;
import com.chronos.pocketos.windows.xserver.Drawable;
import com.chronos.pocketos.windows.xserver.GraphicsContext;
import com.chronos.pocketos.windows.core.Bitmask;
import com.chronos.pocketos.windows.xserver.XClient;
import com.chronos.pocketos.windows.xserver.errors.BadDrawable;
import com.chronos.pocketos.windows.xserver.errors.BadGraphicsContext;
import com.chronos.pocketos.windows.xserver.errors.BadIdChoice;
import com.chronos.pocketos.windows.xserver.errors.XRequestError;

public abstract class GraphicsContextRequests {
    public static void createGC(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int gcId = inputStream.readInt();
        int drawableId = inputStream.readInt();
        Bitmask valueMask = new Bitmask(inputStream.readInt());

        if (!client.isValidResourceId(gcId)) throw new BadIdChoice(gcId);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.createGraphicsContext(gcId, drawable);
        if (graphicsContext == null) throw new BadIdChoice(gcId);

        client.registerAsOwnerOfResource(graphicsContext);
        if (!valueMask.isEmpty()) client.xServer.graphicsContextManager.updateGraphicsContext(graphicsContext, valueMask, inputStream);
    }

    public static void changeGC(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int gcId = inputStream.readInt();
        Bitmask valueMask = new Bitmask(inputStream.readInt());
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (!valueMask.isEmpty()) client.xServer.graphicsContextManager.updateGraphicsContext(graphicsContext, valueMask, inputStream);
    }

    public static void freeGC(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        client.xServer.graphicsContextManager.freeGraphicsContext(inputStream.readInt());
    }
}
