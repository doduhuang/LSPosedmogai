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

import android.app.LoadedApk;
import android.content.res.XResources;
import android.util.Log;

import com.debin.android.fun.XC_MethodHook;
import com.debin.android.fun.XpoHelpers;
import com.debin.android.fun.XpoInit;
import com.posed.lspd.util.Hookers;

// when a package is loaded for an existing process, trigger the callbacks as well
public class LoadedApkCtorHooker extends XC_MethodHook {

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        Hookers.logD("LoadedApk#<init> starts");

        try {
            LoadedApk loadedApk = (LoadedApk) param.thisObject;
            String packageName = loadedApk.getPackageName();
            Object mAppDir = XpoHelpers.getObjectField(loadedApk, "mAppDir");
            Hookers.logD("LoadedApk#<init> ends: " + mAppDir);

            if (!XpoInit.disableResources) {
                XResources.setPackageNameForResDir(packageName, loadedApk.getResDir());
            }

            if (packageName.equals("android")) {
                if (XpoInit.startsSystemServer) {
                    Hookers.logD("LoadedApk#<init> is android, skip: " + mAppDir);
                    return;
                } else {
                    packageName = "system";
                }
            }

            // mIncludeCode checking should go ahead of loadedPackagesInProcess added checking
            if (!XpoHelpers.getBooleanField(loadedApk, "mIncludeCode")) {
                Hookers.logD("LoadedApk#<init> mIncludeCode == false: " + mAppDir);
                return;
            }

            if (!XpoInit.loadedPackagesInProcess.add(packageName)) {
                Hookers.logD("LoadedApk#<init> has been loaded before, skip: " + mAppDir);
                return;
            }

            // OnePlus magic...
            if (Log.getStackTraceString(new Throwable()).
                    contains("android.app.ActivityThread$ApplicationThread.schedulePreload")) {
                Hookers.logD("LoadedApk#<init> maybe oneplus's custom opt, skip");
                return;
            }

            new LoadedApkGetCLHooker(loadedApk);
        } catch (Throwable t) {
            Hookers.logE("error when hooking LoadedApk.<init>", t);
        }
    }
}
