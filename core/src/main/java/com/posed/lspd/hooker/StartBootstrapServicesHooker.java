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

import com.debin.android.fun.XC_MethodHook;
import com.debin.android.fun.XpoBridge;
import com.debin.android.fun.XpoInit;
import com.debin.android.fun.callbacks.XC_LoadPackage;
import com.posed.lspd.util.Hookers;

import static com.posed.lspd.util.Utils.logD;

public class StartBootstrapServicesHooker extends XC_MethodHook {

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        logD("SystemServer#startBootstrapServices() starts");

        try {
            XpoInit.loadedPackagesInProcess.add("android");

            XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XpoBridge.sLoadedPackageCallbacks);
            lpparam.packageName = "android";
            lpparam.processName = "android"; // it's actually system_server, but other functions return this as well
            lpparam.classLoader = HandleSystemServerProcessHooker.systemServerCL;
            lpparam.appInfo = null;
            lpparam.isFirstApplication = true;
            XC_LoadPackage.callAll(lpparam);
        } catch (Throwable t) {
            Hookers.logE("error when hooking startBootstrapServices", t);
        }
    }
}
