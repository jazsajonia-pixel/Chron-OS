package com.winlator.core;

/**
 * PocketOS: androidx.startup only runs in the app's main process, but the runtime lives in ":winruntime".
 * This copy of the provider runs there, so ProcessLifecycleOwner (used by ForegroundService) is initialized.
 */
public class RuntimeStartupProvider extends androidx.startup.InitializationProvider {}
