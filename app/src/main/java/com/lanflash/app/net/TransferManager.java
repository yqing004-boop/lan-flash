package com.lanflash.app.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.lanflash.app.R;
import com.lanflash.app.core.Bus;
import com.lanflash.app.core.Const;
import com.lanflash.app.core.Prefs;
import com.lanflash.app.core.Util;
import com.lanflash.app.model.ChatMessage;
import com.lanflash.app.model.MessageStore;
import com.lanflash.app.model.Peer;
import com.lanflash.app.ui.ChatActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 传输调度：负责发送文件与取消逻辑。接收入口在 Session。
 */
public class TransferManager {

    private static final String TAG = "LanFlashTransfer";

    public static class Job {
        public final String fileId;
        public final ChatMessage msg;
        public volatile boolean cancelled = false;
        public volatile Socket socket;
        public long total;
        public long bytes;
        public long startedAt = System.currentTimeMillis();
        public long lastSampleAt = startedAt;
        public long lastSampleBytes = 0;
        public long speed;

        public Job(String fileId, ChatMessage msg, long total) {
            this.fileId = fileId;
            this.msg = msg;
            this.total = total;
        }

        public int percent() {
            if (total <= 0) return 0;
            int p = (int) (bytes * 100 / total);
            if (p > 100) p = 100;
            if (p < 0) p = 0;
            return p;
        }

        public void cancel() {
            cancelled = true;
            Socket s = socket;
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final MessageStore store;
    private final PeerRegistry peers;
    private final Map<String, Job> jobs = new HashMap<String, Job>();
    private NotificationManager nm;
    private long lastNotifyAt = 0;

    private static final String CH_DONE = "lanflash_done";
    private static final String CH_TRANSFER = "lanflash_transfer";

    public TransferManager(Context ctx, Prefs prefs, MessageStore store, PeerRegistry peers) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
        this.store = store;
        this.peers = peers;
        this.nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannels();
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH_DONE,
                "传输完成提醒", NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
        NotificationChannel ct = new NotificationChannel(CH_TRANSFER,
                ctx.getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW);
        ct.setShowBadge(false);
        ct.setSound(null, null);
        ct.enableVibration(false);
        nm.createNotificationChannel(ct);
    }

    public void notifyDone(String text) {
        try {
            if (nm == null) return;
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_DONE)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("传输完成")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_SOUND);
            nm.notify(2100 + (int) (System.currentTimeMillis() % 1000), b.build());
        } catch (Throwable t) {
            Log.w(TAG, "notify done failed", t);
        }
    }

    public void notifyMessage(String from, String text) {
        try {
            if (nm == null) return;
            String body = (from == null ? "" : from + "：") + (text == null ? "" : text);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_DONE)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("新消息")
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_SOUND);
            nm.notify(3100 + (int) (System.currentTimeMillis() % 1000), b.build());
        } catch (Throwable t) {
            Log.w(TAG, "notify message failed", t);
        }
    }

    // ---------------- 发送 ----------------}

    public void sendFile(final Peer peer, final ChatMessage msg, final Uri uri, final String path) {
        final Job job = new Job(msg.fileId, msg, msg.fileSize);
        register(job);
        new Thread(new Runnable() {
            @Override
            public void run() {
                doSendFile(peer, msg, uri, path, job);
            }
        }, "lanflash-send-" + msg.fileId).start();
    }

    private InputStream openInput(Uri uri, String path) throws Exception {
        if (path != null) {
            File f = new File(path);
            if (f.isFile()) return new FileInputStream(f);
        }
        if (uri == null) throw new IllegalStateException("没有可读取的文件来源");
        ContentResolver cr = ctx.getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IllegalStateException("无法打开文件流");
        return in;
    }

    private void doSendFile(Peer peer, ChatMessage msg, Uri uri, String path, Job job) {
        Socket socket = null;
        InputStream in = null;
        try {
            msg.status = ChatMessage.ST_SENDING;
            msg.progress = -1; // 负数 = 正在算校验值
            Bus.post(Bus.MESSAGE_UPDATED, msg);

            String digest = digestOf(uri, path);
            msg.progress = 0;

            if (job.cancelled) throw new InterruptedException("已取消");

            socket = new Socket();
            job.socket = socket;
            socket.connect(new InetSocketAddress(peer.host, peer.port), Const.SOCKET_TIMEOUT_MS);
            socket.setSoTimeout((int) (Const.TRUST_WAIT_MS + 5000));
            socket.setSendBufferSize(256 * 1024);
            socket.setTcpNoDelay(true);
            Protocol.Reader reader = new Protocol.Reader(socket.getInputStream());
            Protocol.Writer writer = new Protocol.Writer(socket.getOutputStream());

            JSONObject hello = new JSONObject();
            hello.put("magic", Protocol.MAGIC);
            hello.put("proto", Const.PROTO_VERSION);
            hello.put("deviceId", prefs.deviceId());
            hello.put("nickname", prefs.nickname());
            hello.put("port", 0);
            writer.writeJson(Protocol.T_HELLO, hello);

            if (!handshake(reader, writer, peer)) {
                throw new IllegalStateException("对方未确认连接");
            }

            JSONObject offer = new JSONObject();
            offer.put("fileId", msg.fileId);
            offer.put("fileName", msg.fileName);
            offer.put("fileSize", msg.fileSize);
            offer.put("chunkSize", Const.CHUNK_SIZE);
            offer.put("sha256", digest);
            writer.writeJson(Protocol.T_FILE_OFFER, offer);

            // 等接收方给出已收到的块位图（断点续传）
            java.util.BitSet received = new java.util.BitSet();
            long deadline = System.currentTimeMillis() + Const.SOCKET_TIMEOUT_MS;
            while (true) {
                Protocol.Frame f = reader.read();
                if (f == null) throw new IllegalStateException("连接被中断");
                if (f.type == Protocol.T_FILE_ACCEPT) {
                    received = Protocol.bitsetFromBase64(f.json().optString("bitmap", ""));
                    break;
                } else if (f.type == Protocol.T_FILE_REJECT) {
                    throw new IllegalStateException("对方拒绝接收: " + f.json().optString("reason", "未知"));
                } else if (f.type == Protocol.T_CANCEL) {
                    throw new InterruptedException("对方取消");
                } else if (f.type == Protocol.T_BYE) {
                    throw new IllegalStateException("对方断开连接");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("等待接收确认超时");
                }
            }

            socket.setSoTimeout(Const.SOCKET_TIMEOUT_MS);

            in = openInput(uri, path);
            int chunk = Const.CHUNK_SIZE;
            byte[] buf = new byte[chunk];
            int totalChunks = (int) ((msg.fileSize + chunk - 1) / chunk);
            int startChunk = 0;
            if (!received.isEmpty()) {
                while (startChunk < totalChunks && received.get(startChunk)) startChunk++;
                long skipped = (long) startChunk * chunk;
                long remain = skipped;
                byte[] skipBuf = new byte[64 * 1024];
                while (remain > 0) {
                    long n = in.skip(remain);
                    if (n <= 0) {
                        int want = (int) Math.min(skipBuf.length, remain);
                        int r = in.read(skipBuf, 0, want);
                        if (r < 0) break;
                        n = r;
                    }
                    remain -= n;
                }
                if (remain > 0) throw new IllegalStateException("源文件读取不完整，无法续传");
            }
            job.bytes = (long) startChunk * chunk;
            long sent = job.bytes;
            int index = startChunk;
            long lastUi = 0;
            while (!job.cancelled) {
                int n = readFull(in, buf);
                if (n < 0) break;
                writer.writeChunk(index, buf, 0, n);
                sent += n;
                index++;
                job.bytes = sent;
                sampleSpeed(job);
                long now = System.currentTimeMillis();
                if (now - lastUi > 200) {
                    lastUi = now;
                    msg.progress = job.percent();
                    msg.speed = job.speed;
                    Bus.post(Bus.MESSAGE_UPDATED, msg);
                    updateNotification(job, msg.fileName);
                }
            }
            if (job.cancelled) throw new InterruptedException("已取消");

            JSONObject done = new JSONObject();
            done.put("fileId", msg.fileId);
            done.put("sha256", digest);
            done.put("size", msg.fileSize);
            writer.writeJson(Protocol.T_FILE_DONE, done);

            socket.setSoTimeout(120000);
            boolean ok = false;
            String resultSha = digest;
            String err = "";
            while (true) {
                Protocol.Frame f = reader.read();
                if (f == null) break;
                if (f.type == Protocol.T_FILE_RESULT) {
                    ok = f.json().optBoolean("ok", false);
                    resultSha = f.json().optString("sha256", digest);
                    err = f.json().optString("error", "");
                    break;
                }
                if (f.type == Protocol.T_BYE) break;
            }
            if (!ok) throw new IllegalStateException("接收方校验失败: " + err);

            msg.status = ChatMessage.ST_DONE;
            msg.progress = 100;
            msg.sha256 = resultSha;
            msg.speed = 0;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            notifyDone("已发送 " + msg.fileName);
            Bus.toast("已发送 " + msg.fileName);
        } catch (InterruptedException e) {
            msg.status = ChatMessage.ST_CANCELLED;
            msg.error = "已取消";
            msg.speed = 0;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
        } catch (Throwable t) {
            Log.w(TAG, "send file failed", t);
            String failReason = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            msg.status = ChatMessage.ST_FAILED;
            msg.error = failReason;
            msg.speed = 0;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            Bus.toast("发送失败：" + failReason);
        } finally {
            Util.closeQuietly(in);
            Util.closeQuietly(socket);
            unregister(job);
        }
    }

    /** 客户端握手：HELLO 后可能收到 TRUST_REQUEST，需继续等对方最终答复。 */
    public boolean handshake(Protocol.Reader reader, Protocol.Writer writer, Peer peer) throws Exception {
        long deadline = System.currentTimeMillis() + Const.TRUST_WAIT_MS + 5000;
        while (System.currentTimeMillis() < deadline) {
            Protocol.Frame f = reader.read();
            if (f == null) return false;
            if (f.type == Protocol.T_HELLO_ACK) {
                JSONObject o = f.json();
                if (!o.optBoolean("ok", true)) {
                    String why = o.optString("reason", "");
                    throw new IllegalStateException(why.length() == 0 ? "对方拒绝连接" : why);
                }
                peer.nickname = o.optString("nickname", peer.nickname);
                peer.trusted = true;
                peer.lastSeen = System.currentTimeMillis();
                peers.touch(peer.deviceId, peer.host, peer.port);
                return true;
            }
            if (f.type == Protocol.T_TRUST_REQUEST) {
                Bus.post(Bus.TOAST, "等待对方确认连接…");
                continue;
            }
            if (f.type == Protocol.T_TRUST_DENIED) {
                return false;
            }
            if (f.type == Protocol.T_BYE) {
                return false;
            }
        }
        return false;
    }

    private void sampleSpeed(Job job) {
        long now = System.currentTimeMillis();
        long dt = now - job.lastSampleAt;
        if (dt >= 1000) {
            long db = job.bytes - job.lastSampleBytes;
            job.speed = db * 1000 / Math.max(1, dt);
            job.lastSampleAt = now;
            job.lastSampleBytes = job.bytes;
        }
    }

    private static int readFull(InputStream in, byte[] buf) throws java.io.IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        return off == 0 ? -1 : off;
    }

    /** 流式 SHA-256（带路径缓存，重复发同一文件不必重算）。 */
    private final Map<String, String> shaCache = new HashMap<String, String>();

    private String digestOf(Uri uri, String path) throws Exception {
        String key = null;
        if (path != null) {
            try {
                File f = new File(path);
                if (f.isFile()) {
                    key = path + "|" + f.length() + "|" + f.lastModified();
                }
            } catch (Throwable ignored) {
            }
        }
        if (key != null) {
            synchronized (shaCache) {
                String hit = shaCache.get(key);
                if (hit != null && hit.length() == 64) return hit;
            }
        }
        InputStream in = openInput(uri, path);
        try {
            String d = Util.sha256(in);
            if (key != null && d != null && d.length() == 64) {
                synchronized (shaCache) {
                    if (shaCache.size() > 200) shaCache.clear();
                    shaCache.put(key, d);
                }
            }
            return d;
        } finally {
            Util.closeQuietly(in);
        }
    }

    // ---------------- 任务表 ----------------}

    public void register(Job job) {
        synchronized (jobs) {
            jobs.put(job.fileId, job);
        }
    }

    public void unregister(Job job) {
        synchronized (jobs) {
            jobs.remove(job.fileId);
        }
    }

    public boolean cancel(String fileId) {
        Job job;
        synchronized (jobs) {
            job = jobs.get(fileId);
        }
        if (job == null) return false;
        job.cancelled = true;
        job.msg.status = ChatMessage.ST_CANCELLED;
        job.msg.speed = 0;
        if (job.msg.error == null || job.msg.error.length() == 0) job.msg.error = "已取消";
        store.update(job.msg);
        Bus.post(Bus.MESSAGE_UPDATED, job.msg);
        Socket s = job.socket;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    public Job findJob(String fileId) {
        synchronized (jobs) {
            return jobs.get(fileId);
        }
    }

    public List<Job> activeJobs() {
        synchronized (jobs) {
            return new ArrayList<Job>(jobs.values());
        }
    }

    // ---------------- 通知 ----------------}

    private void updateNotification(Job job, String name) {
        long now = System.currentTimeMillis();
        boolean finished = job.total > 0 && job.bytes >= job.total;
        if (!finished && now - lastNotifyAt < 2000) return;
        lastNotifyAt = now;
        if (nm == null) return;
        try {
            Intent i = new Intent(ctx, ChatActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_TRANSFER)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(ctx.getString(R.string.transfer_notif_title))
                    .setContentText((name == null ? "" : name) + " · " + job.percent() + "%")
                    .setOngoing(!finished)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pi);
            if (job.total > 0) {
                b.setProgress(100, job.percent(), false);
            }
            if (job.cancelled) {
                b.setContentTitle(ctx.getString(R.string.file_cancelled)).setProgress(0, 0, false);
            }
            Notification n = b.build();
            nm.notify(Const.NOTIF_ID_TRANSFER, n);
            if (finished || job.cancelled) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            nm.cancel(Const.NOTIF_ID_TRANSFER);
                        } catch (Throwable ignored) {
                        }
                    }
                }, 2500);
            }
        } catch (Throwable t) {
            Log.w(TAG, "notify failed", t);
        }
    }

    public void updateProgress(Job job) {
        updateNotification(job, job.msg == null ? "" : job.msg.fileName);
    }
}
