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

package com.posed.lspd.util;

import android.os.IBinder;

import com.debin.android.fun.XpoHelpers;

public class InstallerVerifier {

    public static boolean sendBinderToManager(final ClassLoader classLoader, IBinder binder) {
        Utils.logI("Found FunXP Manager");
        try {
            var clazz = XpoHelpers.findClass("com.posed.manager.Constants", classLoader);
            var ret = (boolean) XpoHelpers.callStaticMethod(clazz, "setBinder",
                    new Class[]{IBinder.class}, binder);
            Utils.logI("Send binder to FunXP Manager: " + ret);
            return ret;
        } catch (Throwable t) {
            Utils.logW("Could not send binder to FunXP Manager", t);
            return false;
        }
    }
}
