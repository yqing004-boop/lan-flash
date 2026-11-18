package com.lanflash.app.net;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Log;

import com.lanflash.app.core.Const;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;

/**
 * 原生 NSD（mDNS）发现，主路径。API 19 只带短 ID，真实 ID 由 TCP 握手补齐。
 */
public class NsdHelper {

    private static final String TAG = "LanFlashNsd";

    public interface Listener {
        void onPeerFound(String host, int port, Map<String, String> attrs);

        void onError(String message);
    }

    private final Context ctx;
    private final Listener listener;
    private final String serviceName;
    private final int port;
    private final String deviceId;
    private final String nickname;

    private NsdManager nsd;
    private NsdManager.RegistrationListener regListener;
    private NsdManager.DiscoveryListener discListener;

    private final Queue<NsdServiceInfo> resolveQueue = new LinkedList<NsdServiceInfo>();
    private boolean resolving = false;
    private String resolvingName;
    private volatile boolean running = false;

    public NsdHelper(Context ctx, String deviceId, String nickname, int port, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.deviceId = deviceId;
        this.nickname = nickname;
        this.port = port;
        this.listener = l;
        this.serviceName = "lf-" + com.lanflash.app.core.Util.shortId(deviceId);
    }

    public String serviceName() {
        return serviceName;
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) {
                listener.onError("系统没有 NSD 服务");
                return;
            }
            register();
            discover();
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
            listener.onError("NSD 启动失败: " + t.getClass().getSimpleName());
        }
    }

    private NsdServiceInfo buildInfo() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(Const.SERVICE_TYPE);
        info.setPort(port);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                info.setAttribute("deviceId", deviceId);
                info.setAttribute("nickname", nickname == null ? "" : nickname);
                info.setAttribute("protoVersion", String.valueOf(Const.PROTO_VERSION));
                info.setAttribute("port", String.valueOf(port));
            } catch (Throwable t) {
                Log.w(TAG, "setAttribute failed", t);
            }
        }
        return info;
    }

    private void register() {
        NsdServiceInfo info = buildInfo();
        regListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "registered as " + serviceInfo.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "register failed: " + errorCode);
                if (errorCode == 3 || errorCode == 1) {
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (!running) return;
                                    try {
                                        nsd.registerService(buildInfo(), NsdManager.PROTOCOL_DNS_SD, regListener);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }, 600);
                    return;
                }
                listener.onError("NSD 注册失败(" + errorCode + ")，使用组播兜底");
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener);
        } catch (Throwable t) {
            Log.w(TAG, "registerService throw", t);
        }
    }

    private void discover() {
        discListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "startDiscovery failed: " + errorCode);
                listener.onError("NSD 发现启动失败(" + errorCode + ")");
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (!running) return;
                String t = info == null ? null : info.getServiceType();
                if (t == null) return;
                t = t.toLowerCase(Locale.US);
                if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
                if (!t.startsWith(Const.SERVICE_TYPE.toLowerCase(Locale.US))) return;
                if (serviceName.equals(info.getServiceName())) return;
                enqueueResolve(info);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                // 统一按 30 秒过期处理，避免列表闪烁
            }
        };
        try {
            nsd.discoverServices(Const.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener);
        } catch (Throwable t) {
            Log.w(TAG, "discoverServices throw", t);
        }
    }

    private void enqueueResolve(NsdServiceInfo info) {
        synchronized (resolveQueue) {
            if (info.getServiceName() != null && info.getServiceName().equals(resolvingName)) return;
            for (NsdServiceInfo q : resolveQueue) {
                if (q.getServiceName().equals(info.getServiceName())) return;
            }
            resolveQueue.add(info);
            if (resolving) return;
            resolving = true;
        }
        resolveNext();
    }

    private void resolveNext() {
        final NsdServiceInfo info;
        synchronized (resolveQueue) {
            info = resolveQueue.poll();
            resolvingName = info == null ? null : info.getServiceName();
            if (info == null) {
                resolving = false;
                return;
            }
        }
        NsdManager.ResolveListener rl = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "resolve failed " + serviceInfo.getServiceName() + ": " + errorCode);
                synchronized (resolveQueue) {
                    resolving = false;
                }
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        resolveNext();
                    }
                }, 300);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                try {
                    if (!running) return;
                    String host = null;
                    InetAddress a = serviceInfo.getHost();
                    if (a != null) host = a.getHostAddress();

                    Map<String, String> attrs = new HashMap<String, String>();
                    if (Build.VERSION.SDK_INT >= 21) {
                        try {
                            Map<String, byte[]> raw = serviceInfo.getAttributes();
                            if (raw != null) {
                                for (Map.Entry<String, byte[]> e : raw.entrySet()) {
                                    try {
                                        attrs.put(e.getKey(), new String(e.getValue(), "UTF-8"));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!attrs.containsKey("deviceId")) {
                        String sn = serviceInfo.getServiceName();
                        if (sn != null && sn.startsWith("lf-")) {
                            attrs.put("shortId", sn.substring(3));
                        }
                    }
                    int p = serviceInfo.getPort();
                    if (p <= 0) {
                        try {
                            p = Integer.parseInt(attrs.get("port"));
                        } catch (Throwable ignored) {
                        }
                    }
                    if (host != null && p > 0) {
                        listener.onPeerFound(host, p, attrs);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "handle resolved failed", t);
                } finally {
                    synchronized (resolveQueue) {
                        resolving = false;
                        resolvingName = null;
                    }
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            resolveNext();
                        }
                    }, 120);
                }
            }
        };
        try {
            nsd.resolveService(info, rl);
        } catch (Throwable t) {
            Log.w(TAG, "resolveService throw", t);
            synchronized (resolveQueue) {
                resolving = false;
            }
        }
    }

    public void stop() {
        running = false;
        if (nsd == null) return;
        if (discListener != null) {
            try {
                nsd.stopServiceDiscovery(discListener);
            } catch (Throwable ignored) {
            }
            discListener = null;
        }
        if (regListener != null) {
            try {
                nsd.unregisterService(regListener);
            } catch (Throwable ignored) {
            }
            regListener = null;
        }
        synchronized (resolveQueue) {
            resolveQueue.clear();
        }
    }
}
