package com.lanflash.app.core;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简进程内事件总线：Service 与 Activity 之间通信，回调统一在主线程。
 */
public final class Bus {

    public static final String PEERS_CHANGED = "peers_changed";
    public static final String PEER_UPDATED = "peer_updated";
    public static final String MESSAGE_NEW = "message_new";
    public static final String MESSAGE_UPDATED = "message_updated";
    public static final String TRUST_REQUEST = "trust_request";
    public static final String FILE_REQUEST = "file_request";
    public static final String SERVICE_STATE = "service_state";
    public static final String MANUAL_RESULT = "manual_result";
    public static final String TOAST = "toast";

    public interface Listener {
        void onEvent(String type, Object payload);
    }

    private static final List<Listener> LISTENERS = new ArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bus() {}

    public static void register(Listener l) {
        if (l == null) return;
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(l)) LISTENERS.add(l);
        }
    }

    public static void unregister(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.remove(l);
        }
    }

    public static void post(final String type, final Object payload) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                List<Listener> snapshot;
                synchronized (LISTENERS) {
                    snapshot = new ArrayList<Listener>(LISTENERS);
                }
                for (Listener l : snapshot) {
                    try {
                        l.onEvent(type, payload);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    public static void toast(String msg) {
        post(TOAST, msg);
    }
}
