package com.winlator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Headless, shell-permission-protected entry point for ADB control. */
public final class AdbControlReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AdbControlHandler.handle(context, intent, false);
    }
}
