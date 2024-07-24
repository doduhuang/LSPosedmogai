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

package com.debin.android.fun;

import static com.posed.lspd.core.ApplicationServiceClient.serviceClient;
import static com.posed.lspd.deopt.PrebuiltMethodsDeopter.deoptResourceMethods;
import static com.debin.android.fun.XpoHelpers.callMethod;
import static com.debin.android.fun.XpoHelpers.findAndHookMethod;

import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.ResourcesImpl;
import android.content.res.TypedArray;
import android.content.res.XResources;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import com.debin.android.fun.callbacks.XC_InitPackageResources;
import com.debin.android.fun.callbacks.XCallback;

import com.posed.lspd.models.PreLoadedApk;
import com.posed.lspd.nativebridge.NativeAPI;
import com.posed.lspd.nativebridge.ResourcesHook;
import com.posed.lspd.util.LspModuleClassLoader;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

import hidden.HiddenApiBridge;

public final class XpoInit {
    private static final String TAG = XpoBridge.TAG;
    public static boolean startsSystemServer = false;

    public static volatile boolean disableResources = false;
    public static AtomicBoolean resourceInit = new AtomicBoolean(false);

    public static void hookResources() throws Throwable {
        if (disableResources || !resourceInit.compareAndSet(false, true)) {
            return;
        }

        deoptResourceMethods();

        if (!ResourcesHook.initXResourcesNative()) {
            Log.e(TAG, "Cannot hook resources");
            disableResources = true;
            return;
        }

        XpoHelpers.findAndHookMethod("android.app.ApplicationPackageManager", null, "getResourcesForApplication",
                ApplicationInfo.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ApplicationInfo app = (ApplicationInfo) param.args[0];
                        XResources.setPackageNameForResDir(app.packageName,
                                app.uid == Process.myUid() ? app.sourceDir : app.publicSourceDir);
                    }
                });

        /*
         * getTopLevelResources(a)
         *   -> getTopLevelResources(b)
         *     -> key = new ResourcesKey()
         *     -> r = new Resources()
         *     -> mActiveResources.put(key, r)
         *     -> return r
         */

        final Class<?> classGTLR;
        final Class<?> classResKey;
        final ThreadLocal<Object> latestResKey = new ThreadLocal<>();
        final ArrayList<String> createResourceMethods = new ArrayList<>();

        classGTLR = android.app.ResourcesManager.class;
        classResKey = android.content.res.ResourcesKey.class;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createResourceMethods.add("createResources");
            createResourceMethods.add("createResourcesForActivity");
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            createResourceMethods.add("createResources");
        } else {
            createResourceMethods.add("getOrCreateResources");
        }

        final Class<?> classActivityRes = XpoHelpers.findClassIfExists("android.app.ResourcesManager$ActivityResource", classGTLR.getClassLoader());
        var hooker = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // At least on OnePlus 5, the method has an additional parameter compared to AOSP.
                Object activityToken = null;
                try {
                    final int activityTokenIdx = XpoHelpers.getParameterIndexByType(param.method, IBinder.class);
                    activityToken = param.args[activityTokenIdx];
                } catch (NoSuchFieldError ignored) {
                }
                final int resKeyIdx = XpoHelpers.getParameterIndexByType(param.method, classResKey);
                String resDir = (String) XpoHelpers.getObjectField(param.args[resKeyIdx], "mResDir");
                XResources newRes = cloneToXResources(param, resDir);
                if (newRes == null) {
                    return;
                }

                //noinspection SynchronizeOnNonFinalField
                synchronized (param.thisObject) {
                    ArrayList<Object> resourceReferences;
                    if (activityToken != null) {
                        Object activityResources = XpoHelpers.callMethod(param.thisObject, "getOrCreateActivityResourcesStructLocked", activityToken);
                        //noinspection unchecked
                        resourceReferences = (ArrayList<Object>) XpoHelpers.getObjectField(activityResources, "activityResources");
                    } else {
                        //noinspection unchecked
                        resourceReferences = (ArrayList<Object>) XpoHelpers.getObjectField(param.thisObject, "mResourceReferences");
                    }
                    if (activityToken == null || classActivityRes == null) {
                        resourceReferences.add(new WeakReference<>(newRes));
                    } else {
                        // Android S createResourcesForActivity()
                        var activityRes = XpoHelpers.newInstance(classActivityRes);
                        XpoHelpers.setObjectField(activityRes, "resources", new WeakReference<>(newRes));
                        resourceReferences.add(activityRes);
                    }
                }
            }
        };

        for (var createResourceMethod : createResourceMethods) {
            XpoBridge.hookAllMethods(classGTLR, createResourceMethod, hooker);
        }

        XpoHelpers.findAndHookMethod(TypedArray.class, "obtain", Resources.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.getResult() instanceof XResources.XTypedArray) {
                            return;
                        }
                        if (!(param.args[0] instanceof XResources)) {
                            return;
                        }
                        XResources.XTypedArray newResult =
                                new XResources.XTypedArray((Resources) param.args[0]);
                        int len = (int) param.args[1];
                        Method resizeMethod = XpoHelpers.findMethodBestMatch(
                                TypedArray.class, "resize", int.class);
                        resizeMethod.setAccessible(true);
                        resizeMethod.invoke(newResult, len);
                        param.setResult(newResult);
                    }
                });

        // Replace system resources
        XResources systemRes = new XResources(
                (ClassLoader) XpoHelpers.getObjectField(Resources.getSystem(), "mClassLoader"));
        HiddenApiBridge.Resources_setImpl(systemRes, (ResourcesImpl) XpoHelpers.getObjectField(Resources.getSystem(), "mResourcesImpl"));
        systemRes.initObject(null);
        XpoHelpers.setStaticObjectField(Resources.class, "mSystem", systemRes);

        XResources.init(latestResKey);
    }

    private static XResources cloneToXResources(XC_MethodHook.MethodHookParam param, String resDir) {
        Object result = param.getResult();
        if (result == null || result instanceof XResources) {
            return null;
        }

        // Replace the returned resources with our subclass.
        XResources newRes = new XResources(
                (ClassLoader) XpoHelpers.getObjectField(param.getResult(), "mClassLoader"));
        HiddenApiBridge.Resources_setImpl(newRes, (ResourcesImpl) XpoHelpers.getObjectField(param.getResult(), "mResourcesImpl"));
        newRes.initObject(resDir);

        // Invoke handleInitPackageResources().
        if (newRes.isFirstLoad()) {
            String packageName = newRes.getPackageName();
            XC_InitPackageResources.InitPackageResourcesParam resparam = new XC_InitPackageResources.InitPackageResourcesParam(XpoBridge.sInitPackageResourcesCallbacks);
            resparam.packageName = packageName;
            resparam.res = newRes;
            XCallback.callAll(resparam);
        }

        param.setResult(newRes);
        return newRes;
    }

    private static final Set<String> loadedModules = new CopyOnWriteArraySet<>();

    public static Set<String> getLoadedModules() {
        return loadedModules;
    }

    public static void loadModules() {
        var moduleList = serviceClient.getModulesList();
        moduleList.forEach(module -> {
            var apk = module.apkPath;
            var name = module.packageName;
            var file = module.file;
            loadedModules.add(apk); // temporarily add it for XSharedPreference
            if (!loadModule(name, apk, file)) {
                loadedModules.remove(apk);
            }
        });
    }

    /**
     * Load all so from an APK by reading <code>assets/native_init</code>.
     * It will only store the so names but not doing anything.
     */
    private static void initNativeModule(List<String> moduleLibraryNames) {
        moduleLibraryNames.forEach(NativeAPI::recordNativeEntrypoint);
    }

    private static boolean initModule(ClassLoader mcl, String apk, List<String> moduleClassNames) {
        var count = 0;
        for (var moduleClassName : moduleClassNames) {
            try {
                Log.i(TAG, "  Loading class " + moduleClassName);

                Class<?> moduleClass = mcl.loadClass(moduleClassName);

                if (!IXpoMod.class.isAssignableFrom(moduleClass)) {
                    Log.e(TAG, "    This class doesn't implement any sub-interface of IXposedMod, skipping it");
                    continue;
                }

                final Object moduleInstance = moduleClass.newInstance();

                if (moduleInstance instanceof IXpoZygoteInit) {
                    IXpoZygoteInit.StartupParam param = new IXpoZygoteInit.StartupParam();
                    param.modulePath = apk;
                    param.startsSystemServer = startsSystemServer;
                    ((IXpoZygoteInit) moduleInstance).initZygote(param);
                    count++;
                }

                if (moduleInstance instanceof IXpoLoadPackage) {
                    XpoBridge.hookLoadPackage(new IXpoLoadPackage.Wrapper((IXpoLoadPackage) moduleInstance));
                    count++;
                }

                if (moduleInstance instanceof IXpoInitPackageResources) {
                    hookResources();
                    XpoBridge.hookInitPackageResources(new IXpoInitPackageResources.Wrapper((IXpoInitPackageResources) moduleInstance));
                    count++;
                }
            } catch (Throwable t) {
                Log.e(TAG, "    Failed to load class " + moduleClassName, t);
            }
        }
        return count > 0;
    }

    /**
     * Load a module from an APK by calling the init(String) method for all classes defined
     * in <code>assets/xposed_init</code>.
     */
    private static boolean loadModule(String name, String apk, PreLoadedApk file) {
        Log.i(TAG, "Loading module " + name + " from " + apk);

        var sb = new StringBuilder();
        var abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) {
            sb.append(apk).append("!/lib/").append(abi).append(File.pathSeparator);
        }
        var librarySearchPath = sb.toString();

        var initLoader = XpoInit.class.getClassLoader();
        var mcl = LspModuleClassLoader.loadApk(apk, file.preLoadedDexes, librarySearchPath, initLoader);

        try {
            if (mcl.loadClass(XpoBridge.class.getName()).getClassLoader() != initLoader) {
                Log.e(TAG, "  Cannot load module: " + name);
                Log.e(TAG, "  The Xposed API classes are compiled into the module's APK.");
                Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
                Log.e(TAG, "  For details, see: https://api.xposed.info/using.html");
                return false;
            }
        } catch (ClassNotFoundException ignored) {
            return false;
        }
        initNativeModule(file.moduleLibraryNames);
        return initModule(mcl, apk, file.moduleClassNames);
    }

    public final static Set<String> loadedPackagesInProcess = ConcurrentHashMap.newKeySet(1);
}
