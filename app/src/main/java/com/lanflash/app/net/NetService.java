package com.lanflash.app.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.lanflash.app.R;
import com.lanflash.app.core.AppCore;
import com.lanflash.app.core.Bus;
import com.lanflash.app.core.Const;
import com.lanflash.app.core.Prefs;
import com.lanflash.app.core.Util;
import com.lanflash.app.model.ChatMessage;
import com.lanflash.app.model.MessageStore;
import com.lanflash.app.model.Peer;
import com.lanflash.app.ui.MainActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 常驻前台服务：局域网发现 + TCP 服务端 + 传输调度。
 */
public class NetService extends Service {

    private static final String TAG = "LanFlashService";
    private static final String CH_SERVICE = "lanflash_service";

    private volatile String progressText;
    private volatile int progressPercent = -1;
    private static NetService instance;

    public static NetService get() {
        return instance;
    }

    private Prefs prefs;
    private MessageStore store;
    private PeerRegistry peers;
    private TransferManager transfer;

    private volatile ServerSocket server;
    private volatile int port = Const.DEFAULT_TCP_PORT;

    private NsdHelper nsd;
    private MulticastBeacon beacon;
    private volatile boolean multicastStarted = false;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    private java.util.concurrent.ThreadPoolExecutor connPool;
    private HandlerThread workerThread;
    private Handler worker;
    private Handler mainHandler;

    private final Map<String, TrustRequest> pendingTrust = new HashMap<String, TrustRequest>();
    private final Map<String, FileRequest> pendingFiles = new HashMap<String, FileRequest>();

    public static class FileRequest {
        public final String deviceId;
        public final String fileName;
        public final long size;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int answer = 2; // 0=接受 1=拒绝 2=超时

        FileRequest(String deviceId, String fileName, long size) {
            this.deviceId = deviceId;
            this.fileName = fileName;
            this.size = size;
        }
    }

    public static class TrustRequest {
        public final String deviceId;
        public final String nickname;
        public final String host;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int answer = 2; // 0=允许 1=拒绝 2=超时

        TrustRequest(String deviceId, String nickname, String host) {
            this.deviceId = deviceId;
            this.nickname = nickname;
            this.host = host;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppCore core = AppCore.get(this);
        prefs = core.prefs;
        store = core.messages;
        peers = core.peers;
        transfer = new TransferManager(this, prefs, store, peers);
        core.attachService(this);

        connPool = new java.util.concurrent.ThreadPoolExecutor(8, 48, 60L,
                TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>(96),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        workerThread = new HandlerThread("lanflash-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        mainHandler = new Handler(getMainLooper());

        createServiceChannel();
        startForegroundCompat();
        scheduleHousekeeping();
        if (prefs.lanEnabled()) {
            acquireKeepAlive();
            startServer();
            startDiscovery();
        }
        Log.i(TAG, "service started, tcp port=" + port);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroying");
        try {
            stopForeground(true);
        } catch (Throwable t) {
            Log.w(TAG, "stopForeground failed", t);
        }
        releaseKeepAlive();
        if (nsd != null) nsd.stop();
        if (beacon != null) beacon.stop();
        if (server != null) {
            try {
                server.close();
            } catch (Throwable ignored) {
            }
        }
        if (connPool != null) connPool.shutdownNow();
        if (workerThread != null) workerThread.quit();
        synchronized (pendingTrust) {
            for (TrustRequest t : pendingTrust.values()) t.latch.countDown();
            pendingTrust.clear();
        }
        synchronized (pendingFiles) {
            for (FileRequest f : pendingFiles.values()) f.latch.countDown();
            pendingFiles.clear();
        }
        try {
            mainHandler.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        if (instance == this) instance = null;
        AppCore.get(this).attachService(null);
        super.onDestroy();
    }

    // ---------------- 前台通知 ----------------}

    private void createServiceChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel old = nm.getNotificationChannel(CH_SERVICE);
            if (old != null && old.getImportance() == NotificationManager.IMPORTANCE_MIN) {
                nm.deleteNotificationChannel(CH_SERVICE);
                old = null;
            }
            if (old == null) {
                NotificationChannel ch = new NotificationChannel(CH_SERVICE,
                        getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(getString(R.string.service_channel_desc));
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable t) {
            Log.w(TAG, "create service channel failed", t);
        }
    }

    private void startForegroundCompat() {
        try {
            Notification n = buildServiceNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Const.NOTIF_ID_SERVICE, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Const.NOTIF_ID_SERVICE, n);
            }
        } catch (SecurityException se) {
            Log.w(TAG, "startForeground denied, run as background service", se);
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
        }
    }

    private Notification buildServiceNotification() {
        createServiceChannel();
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_SERVICE);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.service_title))
                .setContentText(statusText())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pi);
        if (progressPercent >= 0) {
            b.setProgress(100, progressPercent, false);
        }
        return b.build();
    }

    private String statusText() {
        String t = progressText;
        return (t == null) ? getString(R.string.service_idle) : t;
    }

    public void updateProgress(String name, int percent, boolean sending) {
        int p = percent;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        String n = (name == null) ? "" : name.trim();
        if (n.length() == 0) n = "文件";
        progressText = (sending ? "正在发送 " : "正在接收 ") + n + " · " + p + "%";
        progressPercent = p;
        notifyStatus();
    }

    public void updateIdle() {
        progressText = null;
        progressPercent = -1;
        notifyStatus();
    }

    private void notifyStatus() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(Const.NOTIF_ID_SERVICE, buildServiceNotification());
        } catch (Throwable t) {
            Log.w(TAG, "notify status failed", t);
        }
    }

    // ---------------- TCP 服务端 ----------------}

    private synchronized void startServer() {
        if (server != null && !server.isClosed()) return;
        for (int i = 0; i < Const.PORT_SCAN_RANGE; i++) {
            int candidate = Const.DEFAULT_TCP_PORT + i;
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(candidate));
                server = ss;
                port = candidate;
                break;
            } catch (IOException e) {
                if (ss != null) {
                    try {
                        ss.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (server == null) {
            Log.e(TAG, "no free port, LAN server disabled");
            Bus.toast("无法监听局域网端口，请重启 App");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "lanflash-accept");
        t.setDaemon(true);
        t.start();
    }

    private static final int MAX_INCOMING = 48;
    private final java.util.concurrent.Semaphore connSlots = new java.util.concurrent.Semaphore(MAX_INCOMING);

    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            Socket s = null;
            try {
                s = server.accept();
                s.setTcpNoDelay(true);
                final Socket incoming = s;
                if (!connSlots.tryAcquire()) {
                    Util.closeQuietly(incoming);
                    continue;
                }
                final Session session = new Session(NetService.this, incoming, prefs, store, peers, transfer,
                        new Session.TrustGate() {
                            @Override
                            public boolean askTrust(String deviceId, String nickname, String host) {
                                return NetService.this.askTrustBlocking(deviceId, nickname, host);
                            }
                        },
                        new Session.FileGate() {
                            @Override
                            public boolean askAccept(String deviceId, String fileName, long size) {
                                return NetService.this.askFileAccept(deviceId, fileName, size);
                            }
                        });
                try {
                    connPool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                session.run();
                            } finally {
                                connSlots.release();
                            }
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException rej) {
                    connSlots.release();
                    Util.closeQuietly(incoming);
                }
            } catch (Throwable t) {
                if (server != null && !server.isClosed()) {
                    Log.w(TAG, "accept failed", t);
                }
                Util.closeQuietly(s);
            }
        }
    }

    public int getPort() {
        return port;
    }

    // ---------------- 发现 ----------------}

    public void restartDiscovery() {
        if (!prefs.lanEnabled()) return;
        worker.post(new Runnable() {
            @Override
            public void run() {
                if (nsd != null) {
                    nsd.stop();
                    nsd = null;
                }
                if (beacon != null) {
                    beacon.stop();
                    beacon = null;
                }
                multicastStarted = false;
                startDiscovery();
            }
        });
    }

    private void startDiscovery() {
        try {
            nsd = new NsdHelper(this, prefs.deviceId(), prefs.nickname(), port, new NsdHelper.Listener() {
                @Override
                public void onPeerFound(String host, int p, Map<String, String> attrs) {
                    String deviceId = attrs.get("deviceId");
                    if (deviceId == null || deviceId.length() < 8) {
                        String shortId = attrs.get("shortId");
                        if (shortId == null) return;
                        deviceId = "short-" + shortId;
                    }
                    String nickname = attrs.get("nickname");
                    if (peers.upsert(deviceId, nickname, host, p, Peer.SRC_NSD)) {
                        Bus.post(Bus.PEERS_CHANGED, null);
                    } else {
                        Bus.post(Bus.PEER_UPDATED, peers.get(deviceId));
                    }
                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "nsd: " + message);
                }
            });
            nsd.start();
        } catch (Throwable t) {
            Log.w(TAG, "nsd start failed", t);
        }

        worker.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!prefs.lanEnabled() || instance != NetService.this) return;
                if (!multicastStarted) {
                    startMulticast("NSD 5 秒后叠加组播");
                }
            }
        }, Const.DISCOVERY_FALLBACK_MS);
    }

    private int multicastRetry = 0;

    private void startMulticast(final String reason) {
        if (multicastStarted) return;
        multicastStarted = true;
        Log.i(TAG, "start multicast: " + reason);
        try {
            beacon = new MulticastBeacon(this, prefs, port, new MulticastBeacon.Listener() {
                @Override
                public void onPeer(String deviceId, String nickname, String host, int tcpPort) {
                    if (peers.upsert(deviceId, nickname, host, tcpPort, Peer.SRC_MULTICAST)) {
                        Bus.post(Bus.PEERS_CHANGED, null);
                    }
                }
            });
            if (!beacon.start()) {
                multicastStarted = false;
                beacon = null;
                if (multicastRetry < 3) {
                    multicastRetry++;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!prefs.lanEnabled() || instance != NetService.this) return;
                            startMulticast("retry " + multicastRetry);
                        }
                    }, 10000);
                }
                return;
            }
            multicastRetry = 0;
        } catch (Throwable t) {
            Log.w(TAG, "multicast start failed", t);
            multicastStarted = false;
        }
    }

    private void acquireKeepAlive() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lanflash:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "wifi lock failed", t);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanflash:cpu");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "wake lock failed", t);
        }
    }

    private void releaseKeepAlive() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        wifiLock = null;
        wakeLock = null;
    }

    private void scheduleHousekeeping() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean changed = peers.removeExpired();
                if (changed) Bus.post(Bus.PEERS_CHANGED, null);
                else if (peers.size() > 0) Bus.post(Bus.PEER_UPDATED, null);
                mainHandler.postDelayed(this, Const.HEARTBEAT_MS / 2);
            }
        });
    }

    // ---------------- 信任与文件确认 ----------------}

    private boolean askTrustBlocking(String deviceId, String nickname, String host) {
        TrustRequest req = new TrustRequest(deviceId, nickname, host);
        synchronized (pendingTrust) {
            TrustRequest old = pendingTrust.put(deviceId, req);
            if (old != null) {
                old.answer = 2;
                old.latch.countDown();
            }
        }
        Bus.post(Bus.TRUST_REQUEST, req);
        try {
            req.latch.await(Const.TRUST_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        synchronized (pendingTrust) {
            if (pendingTrust.get(deviceId) == req) pendingTrust.remove(deviceId);
        }
        if (req.answer == 1) {
            prefs.deny(deviceId);
            return false;
        }
        if (req.answer == 2) {
            Bus.toast("信任确认超时，已断开 " + (nickname == null ? Util.shortId(deviceId) : nickname));
            return false;
        }
        prefs.trust(deviceId);
        Peer p = peers.get(deviceId);
        if (p != null) p.trusted = true;
        Bus.post(Bus.PEERS_CHANGED, null);
        return true;
    }

    public boolean askFileAccept(String deviceId, String fileName, long size) {
        FileRequest req = new FileRequest(deviceId, fileName, size);
        synchronized (pendingFiles) {
            FileRequest old = pendingFiles.put(deviceId, req);
            if (old != null) {
                old.answer = 2;
                old.latch.countDown();
            }
        }
        Bus.post(Bus.FILE_REQUEST, req);
        try {
            req.latch.await(Const.TRUST_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        synchronized (pendingFiles) {
            if (pendingFiles.get(deviceId) == req) pendingFiles.remove(deviceId);
        }
        return req.answer == 0;
    }

    public void answerFileAccept(String deviceId, boolean accept) {
        FileRequest req;
        synchronized (pendingFiles) {
            req = pendingFiles.get(deviceId);
        }
        if (req == null) return;
        req.answer = accept ? 0 : 1;
        req.latch.countDown();
    }

    public void answerTrust(String deviceId, boolean allow) {
        TrustRequest req;
        synchronized (pendingTrust) {
            req = pendingTrust.get(deviceId);
        }
        if (req == null) return;
        req.answer = allow ? 0 : 1;
        req.latch.countDown();
    }

    public TrustRequest pendingTrustFor(String deviceId) {
        synchronized (pendingTrust) {
            return pendingTrust.get(deviceId);
        }
    }

    // ---------------- 文本发送 ----------------}

    /** 局域网传输总开关：开/关发现、TCP 服务与保活锁。 */
    public void startLan() {
        if (server == null || server.isClosed()) startServer();
        if (nsd == null) startDiscovery();
        acquireKeepAlive();
    }

    public void stopLan() {
        if (nsd != null) {
            nsd.stop();
            nsd = null;
        }
        if (beacon != null) {
            beacon.stop();
            beacon = null;
        }
        multicastStarted = false;
        multicastRetry = 0;
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
        server = null;
        releaseKeepAlive();
    }


    public void sendText(final Peer peer, final ChatMessage msg) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                doSendText(peer, msg);
            }
        });
    }

    private void doSendText(Peer peer, ChatMessage msg) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(peer.host, peer.port), Const.SOCKET_TIMEOUT_MS);
            s.setSoTimeout((int) (Const.TRUST_WAIT_MS + 5000));
            s.setTcpNoDelay(true);
            Protocol.Reader reader = new Protocol.Reader(s.getInputStream());
            Protocol.Writer writer = new Protocol.Writer(s.getOutputStream());

            JSONObject hello = new JSONObject();
            hello.put("magic", Protocol.MAGIC);
            hello.put("proto", Const.PROTO_VERSION);
            hello.put("deviceId", prefs.deviceId());
            hello.put("nickname", prefs.nickname());
            hello.put("port", port);
            writer.writeJson(Protocol.T_HELLO, hello);

            if (!transfer.handshake(reader, writer, peer)) {
                throw new IllegalStateException("对方未确认连接");
            }

            JSONObject t = new JSONObject();
            t.put("msgId", msg.msgId);
            t.put("fromDeviceId", prefs.deviceId());
            t.put("fromNickname", prefs.nickname());
            t.put("timestamp", msg.timestamp);
            t.put("text", msg.text);
            writer.writeJson(Protocol.T_TEXT, t);

            s.setSoTimeout(Const.SOCKET_TIMEOUT_MS);
            boolean acked = false;
            while (true) {
                Protocol.Frame f = reader.read();
                if (f == null) break;
                if (f.type == Protocol.T_TEXT_ACK) {
                    acked = f.json().optBoolean("ok", false);
                    break;
                }
                if (f.type == Protocol.T_BYE) break;
            }
            if (!acked) throw new IllegalStateException("对方未确认收到");

            msg.status = ChatMessage.ST_SENT;
            msg.error = null;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
        } catch (Throwable t) {
            msg.status = ChatMessage.ST_FAILED;
            msg.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            Bus.toast("发送失败：" + msg.error);
        } finally {
            Util.closeQuietly(s);
        }
    }

    // ---------------- 文件发送 ----------------}

    public void sendFile(Peer peer, ChatMessage msg, Uri uri, String path) {
        transfer.sendFile(peer, msg, uri, path);
    }

    public TransferManager transfers() {
        return transfer;
    }

    // ---------------- 手动添加 ----------------}

    public void connectManual(final String host, final int p) {
        Bus.post(Bus.SERVICE_STATE, "connecting:" + host + ":" + p);
        worker.post(new Runnable() {
            @Override
            public void run() {
                Socket s = null;
                String error = null;
                Peer found = null;
                try {
                    s = new Socket();
                    s.connect(new InetSocketAddress(host, p), Const.SOCKET_TIMEOUT_MS);
                    s.setSoTimeout((int) (Const.TRUST_WAIT_MS + 8000));
                    Protocol.Reader reader = new Protocol.Reader(s.getInputStream());
                    Protocol.Writer writer = new Protocol.Writer(s.getOutputStream());

                    JSONObject hello = new JSONObject();
                    hello.put("magic", Protocol.MAGIC);
                    hello.put("proto", Const.PROTO_VERSION);
                    hello.put("deviceId", prefs.deviceId());
                    hello.put("nickname", prefs.nickname());
                    hello.put("port", port);
                    writer.writeJson(Protocol.T_HELLO, hello);

                    long deadline = System.currentTimeMillis() + Const.TRUST_WAIT_MS + 5000;
                    while (System.currentTimeMillis() < deadline) {
                        Protocol.Frame f = reader.read();
                        if (f == null) {
                            error = "对方关闭了连接";
                            break;
                        }
                        if (f.type == Protocol.T_HELLO_ACK) {
                            JSONObject o = f.json();
                            if (!o.optBoolean("ok", true)) {
                                error = o.optString("reason", "对方拒绝连接");
                                break;
                            }
                            String did = o.optString("deviceId", "");
                            if (did.length() >= 8) {
                                peers.upsert(did, o.optString("nickname", ""), host, p, Peer.SRC_MANUAL);
                                Peer peer = peers.get(did);
                                if (peer != null) {
                                    peer.trusted = prefs.isTrusted(did);
                                    prefs.rememberPeerAddress(did, host, p);
                                    found = peer;
                                }
                            }
                            break;
                        }
                        if (f.type == Protocol.T_TRUST_REQUEST) {
                            Bus.post(Bus.TOAST, "已连接，等待对方在设备上确认…");
                            continue;
                        }
                        if (f.type == Protocol.T_TRUST_DENIED) {
                            error = "对方拒绝连接";
                            break;
                        }
                    }
                    if (found == null && error == null) error = "等待对方确认超时";
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                } finally {
                    Util.closeQuietly(s);
                }
                if (found != null) {
                    Bus.post(Bus.PEERS_CHANGED, null);
                    Bus.post(Bus.MANUAL_RESULT, "ok");
                    Bus.toast("已添加 " + found.displayName());
                } else {
                    Bus.post(Bus.MANUAL_RESULT, "fail:" + Util.safe(error));
                }
            }
        });
    }
}
