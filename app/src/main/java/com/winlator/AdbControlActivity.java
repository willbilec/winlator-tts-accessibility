package com.winlator;

import android.app.Activity;
import android.os.Bundle;

/** Small, shell-permission-protected entry point for ADB diagnostics and automation. */
public final class AdbControlActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AdbControlHandler.handle(this, getIntent(), true);
        finish();
    }
}
