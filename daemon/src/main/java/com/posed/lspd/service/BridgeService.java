package com.posed.lspd.service;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ServiceManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.Map;

public class BridgeService {

    static final int TRANSACTION_CODE = ('_' << 24) | ('L' << 16) | ('S' << 8) | 'P'; // 1598837584
    private static final String DESCRIPTOR = "FunXP";
    private static final String SERVICE_NAME = "activity";

    enum ACTION {
        ACTION_UNKNOWN,
        ACTION_SEND_BINDER,
        ACTION_GET_BINDER,
    }

    public interface Listener {
        void onSystemServerRestarted();

        void onResponseFromBridgeService(boolean response);

        void onSystemServerDied();
    }

    private static IBinder serviceBinder = null;

    private static Listener listener;
    private static IBinder bridgeService;
    private static final IBinder.DeathRecipient bridgeRecipient = new IBinder.DeathRecipient() {

        @Override
        public void binderDied() {
            Log.i(com.posed.lspd.service.ServiceManager.TAG, "service " + SERVICE_NAME + " is dead. ");

            try {
                //noinspection JavaReflectionMemberAccess DiscouragedPrivateApi
                Field field = ServiceManager.class.getDeclaredField("sServiceManager");
                field.setAccessible(true);
                field.set(null, null);

                //noinspection JavaReflectionMemberAccess DiscouragedPrivateApi
                field = ServiceManager.class.getDeclaredField("sCache");
                field.setAccessible(true);
                Object sCache = field.get(null);
                if (sCache instanceof Map) {
                    //noinspection rawtypes
                    ((Map) sCache).clear();
                }
                Log.i(com.posed.lspd.service.ServiceManager.TAG, "clear ServiceManager");
            } catch (Throwable e) {
                Log.w(com.posed.lspd.service.ServiceManager.TAG, "clear ServiceManager: " + Log.getStackTraceString(e));
            }

            bridgeService.unlinkToDeath(this, 0);
            bridgeService = null;
            listener.onSystemServerDied();
            new Handler(Looper.getMainLooper()).post(() -> sendToBridge(serviceBinder, true));
        }
    };

    // For service
    // This MUST run in main thread
    private static synchronized void sendToBridge(IBinder binder, boolean isRestart) {
        assert Looper.myLooper() == Looper.getMainLooper();
        try {
            Os.seteuid(0);
        } catch (ErrnoException e) {
            Log.e(com.posed.lspd.service.ServiceManager.TAG, "seteuid 0", e);
        }
        try {
            do {
                bridgeService = ServiceManager.getService(SERVICE_NAME);
                if (bridgeService != null && bridgeService.pingBinder()) {
                    break;
                }

                Log.i(com.posed.lspd.service.ServiceManager.TAG, "service " + SERVICE_NAME + " is not started, wait 1s.");

                try {
                    //noinspection BusyWait
                    Thread.sleep(1000);
                } catch (Throwable e) {
                    Log.w(com.posed.lspd.service.ServiceManager.TAG, "sleep" + Log.getStackTraceString(e));
                }
            } while (true);

            if (isRestart && listener != null) {
                listener.onSystemServerRestarted();
            }

            try {
                bridgeService.linkToDeath(bridgeRecipient, 0);
            } catch (Throwable e) {
                Log.w(com.posed.lspd.service.ServiceManager.TAG, "linkToDeath " + Log.getStackTraceString(e));
                var snapshot = bridgeService;
                sendToBridge(binder, snapshot == null || !snapshot.isBinderAlive());
                return;
            }

            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            boolean res = false;
            // try at most three times
            for (int i = 0; i < 3; i++) {
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(ACTION.ACTION_SEND_BINDER.ordinal());
                    Log.v(com.posed.lspd.service.ServiceManager.TAG, "binder " + binder.toString());
                    data.writeStrongBinder(binder);
                    if (bridgeService == null) break;
                    res = bridgeService.transact(TRANSACTION_CODE, data, reply, 0);
                    reply.readException();
                } catch (Throwable e) {
                    Log.e(com.posed.lspd.service.ServiceManager.TAG, "send binder " + Log.getStackTraceString(e));
                    var snapshot = bridgeService;
                    sendToBridge(binder, snapshot == null || !snapshot.isBinderAlive());
                    return;
                } finally {
                    data.recycle();
                    reply.recycle();
                }

                if (res) break;

                Log.w(com.posed.lspd.service.ServiceManager.TAG, "no response from bridge, retry in 1s");

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }

            if (listener != null) {
                listener.onResponseFromBridgeService(res);
            }
        } finally {
            try {
                Os.seteuid(1000);
            } catch (ErrnoException e) {
                Log.e(com.posed.lspd.service.ServiceManager.TAG, "seteuid 1000", e);
            }
        }
    }

    public static void send(LSPosedService service, Listener listener) {
        BridgeService.listener = listener;
        BridgeService.serviceBinder = service.asBinder();
        sendToBridge(serviceBinder, false);
    }
}