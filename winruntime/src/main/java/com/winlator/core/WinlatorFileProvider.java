package com.winlator.core;

/** Own subclass so the runtime's provider does not clash with the host app's androidx FileProvider entry. */
public class WinlatorFileProvider extends androidx.core.content.FileProvider {}
