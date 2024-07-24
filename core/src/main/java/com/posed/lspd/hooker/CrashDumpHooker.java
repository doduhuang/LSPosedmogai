package com.posed.lspd.hooker;

import android.util.Log;

import com.debin.android.fun.XC_MethodHook;
import com.debin.android.fun.XpoBridge;

public class CrashDumpHooker extends XC_MethodHook {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            var e = (Throwable) param.args[0];
            XpoBridge.log("Crash unexpectedly: " + Log.getStackTraceString(e));
        } catch (Throwable ignored) {
        }
    }
}
