/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package com.posed.lspd.hooker;

import android.app.ActivityThread;
import android.app.AndroidAppHelper;
import android.app.LoadedApk;
import android.os.IBinder;

import com.debin.android.fun.XC_MethodHook;
import com.debin.android.fun.XC_MethodReplacement;
import com.debin.android.fun.XpoBridge;
import com.debin.android.fun.XpoHelpers;
import com.debin.android.fun.callbacks.XC_LoadPackage;
import com.posed.lspd.util.Hookers;
import com.posed.lspd.util.MetaDataReader;
import com.posed.lspd.util.Utils;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static com.posed.lspd.core.ApplicationServiceClient.serviceClient;

public class LoadedApkGetCLHooker extends XC_MethodHook {
    private final LoadedApk loadedApk;
    private final Unhook unhook;

    public LoadedApkGetCLHooker(LoadedApk loadedApk) {
        this.loadedApk = loadedApk;
        unhook = XpoHelpers.findAndHookMethod(LoadedApk.class, "getClassLoader", this);
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        LoadedApk loadedApk = (LoadedApk) param.thisObject;

        if (loadedApk != this.loadedApk) {
            return;
        }

        try {
            Hookers.logD("LoadedApk#getClassLoader starts");

            String packageName = ActivityThread.currentPackageName();
            String processName = ActivityThread.currentProcessName();
            boolean isFirstApplication = packageName != null && processName != null && packageName.equals(loadedApk.getPackageName());
            if (!isFirstApplication) {
                packageName = loadedApk.getPackageName();
                processName = AndroidAppHelper.currentProcessName();
            } else if (packageName.equals("android")) {
                packageName = "system";
            }

            Object mAppDir = XpoHelpers.getObjectField(loadedApk, "mAppDir");
            ClassLoader classLoader = (ClassLoader) param.getResult();
            Hookers.logD("LoadedApk#getClassLoader ends: " + mAppDir + " -> " + classLoader);

            if (classLoader == null) {
                return;
            }

            XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(
                    XpoBridge.sLoadedPackageCallbacks);
            lpparam.packageName = packageName;
            lpparam.processName = processName;
            lpparam.classLoader = classLoader;
            lpparam.appInfo = loadedApk.getApplicationInfo();
            lpparam.isFirstApplication = isFirstApplication;

            IBinder moduleBinder = serviceClient.requestModuleBinder(lpparam.packageName);
            if (moduleBinder != null) {
                hookNewXSP(lpparam);
            }

            Hookers.logD("Call handleLoadedPackage: packageName=" + lpparam.packageName + " processName=" + lpparam.processName + " isFirstApplication=" + isFirstApplication + " classLoader=" + lpparam.classLoader + " appInfo=" + lpparam.appInfo);
            XC_LoadPackage.callAll(lpparam);

        } catch (Throwable t) {
            Hookers.logE("error when hooking LoadedApk#getClassLoader", t);
        } finally {
            unhook.unhook();
        }
    }

    private void hookNewXSP(XC_LoadPackage.LoadPackageParam lpparam) {
        int xposedminversion = -1;
        boolean xposedsharedprefs = false;
        try {
            Map<String, Object> metaData = MetaDataReader.getMetaData(new File(lpparam.appInfo.sourceDir));
            Object minVersionRaw = metaData.get("xposedminversion");
            if (minVersionRaw instanceof Integer) {
                xposedminversion = (Integer) minVersionRaw;
            } else if (minVersionRaw instanceof String) {
                xposedminversion = MetaDataReader.extractIntPart((String) minVersionRaw);
            }
            xposedsharedprefs = metaData.containsKey("xposedsharedprefs");
        } catch (NumberFormatException | IOException e) {
            Hookers.logE("ApkParser fails", e);
        }

        if (xposedminversion > 92 || xposedsharedprefs) {
            Utils.logW("New modules detected, hook preferences");
            XpoHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "checkMode", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (((int) param.args[0] & 1/*Context.MODE_WORLD_READABLE*/) != 0) {
                        param.setThrowable(null);
                    }
                }
            });
            XpoHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "getPreferencesDir", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return new File(serviceClient.getPrefsPath(lpparam.packageName));
                }
            });
        }
    }
}
