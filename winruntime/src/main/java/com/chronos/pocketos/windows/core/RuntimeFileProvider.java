package com.chronos.pocketos.windows.core;

/** Own subclass so the runtime's provider does not clash with the host app's androidx FileProvider entry. */
public class RuntimeFileProvider extends androidx.core.content.FileProvider {}
