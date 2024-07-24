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

package com.posed.manager;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.text.TextUtils;
import android.util.Log;

import com.posed.manager.adapters.AppHelper;
import com.posed.manager.repo.RepoLoader;
import com.posed.manager.ui.activity.CrashReportActivity;
import com.posed.manager.util.DoHDNS;
import com.posed.manager.util.ModuleUtil;
import com.posed.manager.util.Telemetry;
import com.posed.manager.util.ThemeUtil;
import com.posed.manager.util.UpdateUtil;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import rikka.core.os.FileUtils;
import rikka.material.app.DayNightDelegate;
import rikka.material.app.LocaleDelegate;

public class App extends Application {
    public static final FutureTask<String> HTML_TEMPLATE = new FutureTask<>(() -> readWebviewHTML("template.html"));
    public static final FutureTask<String> HTML_TEMPLATE_DARK = new FutureTask<>(() -> readWebviewHTML("template_dark.html"));

    private static String readWebviewHTML(String name) {
        try {
            var input = App.getInstance().getAssets().open("webview/" + name);
            var result = new ByteArrayOutputStream(1024);
            FileUtils.copy(input, result);
            return result.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            Log.e(App.TAG, "read webview HTML", e);
            return "<html dir\"@dir@\"><body>@body@</body></html>";
        }
    }

    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // TODO: set specific class name
            HiddenApiBypass.addHiddenApiExemptions("");
        }
        Looper.myQueue().addIdleHandler(() -> {
            if (App.getInstance() == null || App.getExecutorService() == null) return true;
            App.getExecutorService().submit(() -> {
                var list = AppHelper.getAppList(false);
                var pm = App.getInstance().getPackageManager();
                list.parallelStream().forEach(i -> AppHelper.getAppLabel(i, pm));
            });
            return false;
        });

        Looper.myQueue().addIdleHandler(() -> {
            if (App.getInstance() == null || App.getExecutorService() == null) return true;
            App.getExecutorService().submit(() -> {
                AppHelper.getDenyList(false);
            });
            return false;
        });
        Looper.myQueue().addIdleHandler(() -> {
            if (App.getInstance() == null || App.getExecutorService() == null) return true;
            App.getExecutorService().submit((Runnable) ModuleUtil::getInstance);
            return false;
        });
        Looper.myQueue().addIdleHandler(() -> {
            if (App.getInstance() == null || App.getExecutorService() == null) return true;
            App.getExecutorService().submit((Runnable) RepoLoader::getInstance);
            return false;
        });
    }

    public static final String TAG = "FunXPManager";
    private static final String ACTION_USER_ADDED = "android.intent.action.USER_ADDED";
    private static final String ACTION_USER_REMOVED = "android.intent.action.USER_REMOVED";
    private static final String ACTION_USER_INFO_CHANGED = "android.intent.action.USER_INFO_CHANGED";
    private static final String EXTRA_REMOVED_FOR_ALL_USERS = "android.intent.extra.REMOVED_FOR_ALL_USERS";
    private static App instance = null;
    private static OkHttpClient okHttpClient;
    private static Cache okHttpCache;
    private SharedPreferences pref;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public static App getInstance() {
        return instance;
    }

    public static SharedPreferences getPreferences() {
        return instance.pref;
    }

    public static ExecutorService getExecutorService() {
        return instance.executorService;
    }

    public static boolean isParasitic() {
        return !Process.isApplicationUid(Process.myUid());
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Telemetry.start(this);
        var map = new HashMap<String, String>(1);
        map.put("isParasitic", String.valueOf(isParasitic()));
        Telemetry.trackEvent("App start", map);
        var am = getSystemService(ActivityManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            map.clear();
            var reasons = am.getHistoricalProcessExitReasons(null, 0, 1);
            if (reasons.size() == 1) {
                map.put("description", reasons.get(0).getDescription());
                map.put("importance", String.valueOf(reasons.get(0).getImportance()));
                map.put("process", reasons.get(0).getProcessName());
                map.put("reason", String.valueOf(reasons.get(0).getReason()));
                map.put("status", String.valueOf(reasons.get(0).getStatus()));
                Telemetry.trackEvent("Last exit reasons", map);
            }
        }
    }

    @SuppressLint("WrongConstant")
    private void setCrashReport() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            String stackTraceString = sw.toString();

            //Reduce data to 128KB so we don't get a TransactionTooLargeException when sending the intent.
            //The limit is 1MB on Android but some devices seem to have it lower.
            //See: http://developer.android.com/reference/android/os/TransactionTooLargeException.html
            //And: http://stackoverflow.com/questions/11451393/what-to-do-on-transactiontoolargeexception#comment46697371_12809171
            if (stackTraceString.length() > 131071) {
                String disclaimer = " [stack trace too large]";
                stackTraceString = stackTraceString.substring(0, 131071 - disclaimer.length()) + disclaimer;
            }
            Intent intent = new Intent(App.this, CrashReportActivity.class);
            intent.putExtra(BuildConfig.APPLICATION_ID + ".EXTRA_STACK_TRACE", stackTraceString);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            App.this.startActivity(intent);
            System.exit(10);
            Process.killProcess(Os.getpid());
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!BuildConfig.DEBUG && !isParasitic()) {
            setCrashReport();
        }

        instance = this;

        pref = PreferenceManager.getDefaultSharedPreferences(this);
        if ("CN".equals(Locale.getDefault().getCountry())) {
            if (!pref.contains("doh")) {
                pref.edit().putBoolean("doh", true).apply();
            }
        }
        DayNightDelegate.setApplicationContext(this);
        DayNightDelegate.setDefaultNightMode(ThemeUtil.getDarkTheme());
        LocaleDelegate.setDefaultLocale(getLocale());
        var res = getResources();
        var config = res.getConfiguration();
        config.setLocale(LocaleDelegate.getDefaultLocale());
        //noinspection deprecation
        res.updateConfiguration(config, res.getDisplayMetrics());

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.posed.manager.NOTIFICATION");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent inIntent) {
                var intent = (Intent) inIntent.getParcelableExtra(Intent.EXTRA_INTENT);
                Log.d(TAG, "onReceive: " + intent);
                switch (intent.getAction()) {
                    case Intent.ACTION_PACKAGE_ADDED:
                    case Intent.ACTION_PACKAGE_CHANGED:
                    case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                    case Intent.ACTION_UID_REMOVED: {
                        var userId = intent.getIntExtra(Intent.EXTRA_USER, 0);
                        var packageName = intent.getStringExtra("android.intent.extra.PACKAGES");
                        var packageRemovedForAllUsers = intent.getBooleanExtra(EXTRA_REMOVED_FOR_ALL_USERS, false);
                        var isXposedModule = intent.getBooleanExtra("isXposedModule", false);
                        if (packageName != null) {
                            if (isXposedModule)
                                ModuleUtil.getInstance().reloadSingleModule(packageName, userId, packageRemovedForAllUsers);
                            else
                                App.getExecutorService().submit(() -> AppHelper.getAppList(true));
                        }
                        break;
                    }
                    case ACTION_USER_ADDED:
                    case ACTION_USER_REMOVED:
                    case ACTION_USER_INFO_CHANGED: {
                        App.getExecutorService().submit(() -> ModuleUtil.getInstance().reloadInstalledModules());
                        break;
                    }
                }
            }
        }, intentFilter);

        UpdateUtil.loadRemoteVersion();

        executorService.submit(HTML_TEMPLATE);
        executorService.submit(HTML_TEMPLATE_DARK);
    }

    @NonNull
    public static OkHttpClient getOkHttpClient() {
        if (okHttpClient == null) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder().cache(getOkHttpCache());
            builder.addInterceptor(chain -> {
                var request = chain.request().newBuilder();
                request.header("User-Agent", TAG);
                return chain.proceed(request.build());
            });
            HttpLoggingInterceptor log = new HttpLoggingInterceptor();
            log.setLevel(HttpLoggingInterceptor.Level.HEADERS);
            if (BuildConfig.DEBUG) builder.addInterceptor(log);
            okHttpClient = builder.dns(new DoHDNS(builder.build())).build();
        }
        return okHttpClient;
    }

    @NonNull
    private static Cache getOkHttpCache() {
        if (okHttpCache == null) {
            okHttpCache = new Cache(new File(App.getInstance().getCacheDir(), "http_cache"), 50L * 1024L * 1024L);
        }
        return okHttpCache;
    }

    public static Locale getLocale(String tag) {
        if (TextUtils.isEmpty(tag) || "SYSTEM".equals(tag)) {
            return LocaleDelegate.getSystemLocale();
        }
        return Locale.forLanguageTag(tag);
    }

    public static Locale getLocale() {
        String tag = getPreferences().getString("language", null);
        return getLocale(tag);
    }
}