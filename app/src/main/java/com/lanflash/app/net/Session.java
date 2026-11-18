package com.lanflash.app.net;

import android.content.Context;
import android.util.Log;

import com.lanflash.app.core.Bus;
import com.lanflash.app.core.Const;
import com.lanflash.app.core.Prefs;
import com.lanflash.app.core.StorageUtil;
import com.lanflash.app.core.Util;
import com.lanflash.app.model.ChatMessage;
import com.lanflash.app.model.MessageStore;
import com.lanflash.app.model.Peer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.util.BitSet;

/**
 * 一条入站 TCP 连接的处理器（服务端侧）。短连接 + 多帧：握手一次后连续处理。
 */
public class Session implements Runnable {

    private static final long MAX_INCOMING_BYTES = 64L * 1024 * 1024 * 1024;
    private static final String TAG = "LanFlashSession";

    public interface FileGate {
        boolean askAccept(String deviceId, String fileName, long size);
    }

    public interface TrustGate {
        boolean askTrust(String deviceId, String nickname, String host);
    }

    private final Context ctx;
    private final Socket socket;
    private final Prefs prefs;
    private final MessageStore store;
    private final PeerRegistry peers;
    private final TransferManager transfer;
    private final TrustGate trustGate;
    private final FileGate fileGate;

    public Session(Context ctx, Socket socket, Prefs prefs, MessageStore store,
                   PeerRegistry peers, TransferManager transfer, TrustGate gate, FileGate fileGate) {
        this.ctx = ctx.getApplicationContext();
        this.socket = socket;
        this.prefs = prefs;
        this.store = store;
        this.peers = peers;
        this.transfer = transfer;
        this.trustGate = gate;
        this.fileGate = fileGate;
    }

    @Override
    public void run() {
        Protocol.Reader reader = null;
        Protocol.Writer writer = null;
        try {
            socket.setSoTimeout(Const.SOCKET_TIMEOUT_MS);
            socket.setReceiveBufferSize(256 * 1024);
            socket.setTcpNoDelay(true);
            reader = new Protocol.Reader(socket.getInputStream());
            writer = new Protocol.Writer(socket.getOutputStream());

            Protocol.Frame first = reader.read();
            if (first == null || first.type != Protocol.T_HELLO) {
                Log.w(TAG, "非 HELLO 首帧，关闭");
                return;
            }
            JSONObject hello = first.json();
            if (!Protocol.MAGIC.equals(hello.optString("magic", ""))) {
                Log.w(TAG, "magic 不匹配，关闭");
                return;
            }
            final String deviceId = hello.optString("deviceId", "");
            final String nickname = hello.optString("nickname", "");
            final int listenPort = hello.optInt("port", 0);
            if (deviceId.length() < 8) return;
            if (deviceId.equals(prefs.deviceId())) return;

            final String host = socket.getInetAddress().getHostAddress();
            Peer peer = peers.get(deviceId);
            if (peer == null) {
                peers.upsert(deviceId, nickname, host,
                        listenPort > 0 ? listenPort : Const.DEFAULT_TCP_PORT, Peer.SRC_MANUAL);
                peer = peers.get(deviceId);
            }
            if (peer == null) return;
            peer.nickname = nickname;
            peer.lastSeen = System.currentTimeMillis();
            boolean trusted = prefs.isTrusted(deviceId);
            if (!trusted) {
                if (listenPort > 0) peer.port = listenPort;
                peer.host = host;
                prefs.rememberPeerAddress(deviceId, host, listenPort > 0 ? listenPort : peer.port);
            }
            if (!trusted) {
                if (prefs.isDenied(deviceId)) {
                    JSONObject o = new JSONObject();
                    o.put("reason", "已被拒绝");
                    writer.writeJson(Protocol.T_TRUST_DENIED, o);
                    return;
                }
                JSONObject req = new JSONObject();
                req.put("deviceId", prefs.deviceId());
                req.put("nickname", prefs.nickname());
                writer.writeJson(Protocol.T_TRUST_REQUEST, req);

                boolean allow = trustGate != null && trustGate.askTrust(deviceId, nickname, host);
                if (!allow) {
                    JSONObject o = new JSONObject();
                    o.put("reason", "对方拒绝");
                    writer.writeJson(Protocol.T_TRUST_DENIED, o);
                    return;
                }
                prefs.trust(deviceId);
                peer.trusted = true;
                Bus.post(Bus.PEERS_CHANGED, null);
            }

            JSONObject ack = new JSONObject();
            ack.put("ok", true);
            ack.put("deviceId", prefs.deviceId());
            ack.put("nickname", prefs.nickname());
            writer.writeJson(Protocol.T_HELLO_ACK, ack);

            while (true) {
                Protocol.Frame f = reader.read();
                if (f == null) break;
                if (f.type == Protocol.T_TEXT) {
                    handleText(f.json(), peer, writer);
                } else if (f.type == Protocol.T_FILE_OFFER) {
                    handleFileOffer(f.json(), peer, reader, writer);
                } else if (f.type == Protocol.T_PING) {
                    writer.writeJson(Protocol.T_PONG, new JSONObject());
                } else if (f.type == Protocol.T_CANCEL) {
                    String fid = f.json().optString("fileId", "");
                    transfer.cancel(fid);
                } else if (f.type == Protocol.T_BYE) {
                    break;
                }
            }
        } catch (Throwable t) {
            String m = t.getMessage();
            if (m != null && m.contains("Socket closed")) {
                Log.i(TAG, "socket closed");
            } else {
                Log.w(TAG, "session error", t);
            }
        } finally {
            Util.closeQuietly(socket);
        }
    }

    private void handleText(JSONObject o, Peer peer, Protocol.Writer writer) {
        try {
            String text = o.optString("text", "");
            if (text.length() == 0) return;
            ChatMessage msg = new ChatMessage();
            msg.msgId = o.optString("msgId", msg.msgId);
            msg.peerDeviceId = peer.deviceId;
            msg.direction = ChatMessage.DIR_IN;
            msg.type = ChatMessage.TYPE_TEXT;
            msg.text = text;
            msg.timestamp = o.optLong("timestamp", System.currentTimeMillis());
            msg.status = ChatMessage.ST_SENT;
            store.append(msg);
            peer.lastSeen = System.currentTimeMillis();
            peers.touch(peer.deviceId, peer.host, peer.port);
            Bus.post(Bus.MESSAGE_NEW, msg);
            Bus.post(Bus.PEERS_CHANGED, null);
            transfer.notifyMessage(peer.displayName(), text);

            JSONObject ack = new JSONObject();
            ack.put("msgId", msg.msgId);
            ack.put("ok", true);
            writer.writeJson(Protocol.T_TEXT_ACK, ack);
        } catch (Throwable t) {
            Log.w(TAG, "handleText failed", t);
        }
    }

    // ---------------- 文件接收 ----------------

    private void handleFileOffer(JSONObject offer, Peer peer, Protocol.Reader reader, Protocol.Writer writer) {
        String fileId = offer.optString("fileId", "");
        String fileName = Util.safeFileName(offer.optString("fileName", "file"));
        long fileSize = offer.optLong("fileSize", 0);
        int chunkSize = offer.optInt("chunkSize", Const.CHUNK_SIZE);
        String sha = offer.optString("sha256", "");
        if (fileId.length() == 0 || fileSize <= 0 || chunkSize <= 0) {
            reject(writer, fileId, "参数不合法");
            return;
        }
        if (chunkSize > Const.MAX_FRAME_BYTES - 8) {
            reject(writer, fileId, "分块过大");
            return;
        }
        if (fileSize > MAX_INCOMING_BYTES) {
            reject(writer, fileId, "文件过大或大小非法");
            return;
        }

        if (!prefs.autoAcceptFiles()) {
            boolean accept = fileGate != null && fileGate.askAccept(peer.deviceId, fileName, fileSize);
            if (!accept) {
                reject(writer, fileId, "对方未接受文件");
                return;
            }
        }

        File dir = StorageUtil.downloadDir(ctx);
        if (dir == null) {
            reject(writer, fileId, "无法创建保存目录");
            return;
        }
        long free = dir.getUsableSpace();
        if (free <= 0 || free < fileSize + 8L * 1024 * 1024) {
            reject(writer, fileId, "对方存储空间不足");
            return;
        }

        int totalChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);
        File partFile = new File(dir, ".part-" + fileId);
        File stateFile = new File(dir, ".state-" + fileId);

        BitSet bits = new BitSet(totalChunks);
        boolean resumable = false;
        if (partFile.isFile() && stateFile.isFile()) {
            try {
                String s = readSmall(stateFile);
                JSONObject st = new JSONObject(s);
                if (st.optLong("size", -1) == fileSize
                        && st.optInt("chunkSize", -1) == chunkSize
                        && st.optInt("total", -1) == totalChunks
                        && partFile.length() == fileSize) {
                    bits = Protocol.bitsetFromBase64(st.optString("bits", ""));
                    resumable = true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "resume state broken, restart", t);
            }
        }
        if (!resumable) {
            if (partFile.exists()) partFile.delete();
            if (stateFile.exists()) stateFile.delete();
            bits.clear();
        }

        try {
            JSONObject acc = new JSONObject();
            acc.put("fileId", fileId);
            acc.put("bitmap", Protocol.bitsetToBase64(bits));
            acc.put("chunkSize", chunkSize);
            writer.writeJson(Protocol.T_FILE_ACCEPT, acc);
        } catch (Throwable t) {
            Log.w(TAG, "send accept failed", t);
            return;
        }

        ChatMessage msg;
        boolean isNew = false;
        ChatMessage existing = store.find(peer.deviceId, "file-" + fileId);
        if (existing != null) {
            msg = existing;
            msg.fileName = fileName;
            msg.fileSize = fileSize;
            msg.sha256 = sha;
            msg.status = ChatMessage.ST_RECEIVING;
            msg.error = null;
            msg.speed = 0;
            msg.progress = (int) (Protocol.countSet(bits, totalChunks) * 100L / Math.max(1, totalChunks));
            store.update(msg);
        } else {
            msg = new ChatMessage();
            msg.msgId = "file-" + fileId;
            msg.peerDeviceId = peer.deviceId;
            msg.direction = ChatMessage.DIR_IN;
            msg.type = ChatMessage.TYPE_FILE;
            msg.fileId = fileId;
            msg.fileName = fileName;
            msg.fileSize = fileSize;
            msg.sha256 = sha;
            msg.timestamp = System.currentTimeMillis();
            msg.status = ChatMessage.ST_RECEIVING;
            msg.progress = (int) (Protocol.countSet(bits, totalChunks) * 100L / Math.max(1, totalChunks));
            store.append(msg);
            isNew = true;
        }
        Bus.post(isNew ? Bus.MESSAGE_NEW : Bus.MESSAGE_UPDATED, msg);

        TransferManager.Job job = new TransferManager.Job(fileId, msg, fileSize);
        job.bytes = (long) Protocol.countSet(bits, totalChunks) * chunkSize;
        if (job.bytes > fileSize) job.bytes = fileSize;
        job.socket = socket;
        transfer.register(job);

        RandomAccessFile raf = null;
        boolean ok = false;
        String error = "";
        try {
            raf = new RandomAccessFile(partFile, "rw");
            raf.setLength(fileSize);

            long lastStateAt = 0;
            long lastUiAt = 0;
            long lastSpeedAt = System.currentTimeMillis();
            long lastSpeedBytes = job.bytes;
            long deadline = System.currentTimeMillis() + 10 * 60 * 1000L;

            while (!job.cancelled) {
                if (System.currentTimeMillis() > deadline) {
                    error = "接收超时";
                    break;
                }
                Protocol.Frame f = reader.read();
                if (f == null) {
                    error = "连接中断";
                    break;
                }
                if (f.type == Protocol.T_CHUNK) {
                    if (f.payload.length < 5) continue;
                    int idx = ((f.payload[0] & 0xFF) << 24) | ((f.payload[1] & 0xFF) << 16)
                            | ((f.payload[2] & 0xFF) << 8) | (f.payload[3] & 0xFF);
                    int len = f.payload.length - 4;
                    if (idx < 0 || idx >= totalChunks) continue;
                    long off = (long) idx * chunkSize;
                    if (off + len > fileSize) len = (int) (fileSize - off);
                    if (len <= 0) continue;
                    raf.seek(off);
                    raf.write(f.payload, 4, len);
                    bits.set(idx);
                    job.bytes += len;
                    if (job.bytes > fileSize) job.bytes = fileSize;

                    long now = System.currentTimeMillis();
                    if (now - lastSpeedAt >= 1000) {
                        job.speed = (job.bytes - lastSpeedBytes) * 1000L / Math.max(1L, now - lastSpeedAt);
                        lastSpeedAt = now;
                        lastSpeedBytes = job.bytes;
                    }
                    if (now - lastUiAt > 200) {
                        lastUiAt = now;
                        msg.progress = job.percent();
                        msg.speed = job.speed;
                        Bus.post(Bus.MESSAGE_UPDATED, msg);
                        transfer.updateProgress(job);
                    }
                    if (now - lastStateAt > 1500) {
                        lastStateAt = now;
                        writeState(stateFile, fileSize, chunkSize, totalChunks, bits);
                    }
                } else if (f.type == Protocol.T_FILE_DONE) {
                    String senderSha = f.json().optString("sha256", sha);
                    if (senderSha.length() > 0) sha = senderSha;
                    int got = 0;
                    for (int i = 0; i < totalChunks; i++) if (bits.get(i)) got++;
                    if (got != totalChunks) {
                        error = "对方提前结束，缺 " + (totalChunks - got) + " 块";
                        break;
                    }
                    ok = true;
                    break;
                } else if (f.type == Protocol.T_CANCEL) {
                    error = "对方取消";
                    break;
                } else if (f.type == Protocol.T_BYE) {
                    error = "对方断开";
                    break;
                } else if (f.type == Protocol.T_PING) {
                    writer.writeJson(Protocol.T_PONG, new JSONObject());
                }
            }
            if (job.cancelled) error = "已取消";
        } catch (Throwable t) {
            Log.w(TAG, "receive failed", t);
            error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        } finally {
            Util.closeQuietly(raf);
            writeState(stateFile, fileSize, chunkSize, totalChunks, bits);
            transfer.unregister(job);
        }

        if (!ok) {
            boolean cancelled = job.cancelled;
            msg.status = cancelled ? ChatMessage.ST_CANCELLED : ChatMessage.ST_FAILED;
            msg.error = error;
            msg.speed = 0;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            try {
                JSONObject r = new JSONObject();
                r.put("fileId", fileId);
                r.put("ok", false);
                r.put("error", error);
                writer.writeJson(Protocol.T_FILE_RESULT, r);
            } catch (Throwable ignored) {
            }
            return;
        }

        // SHA-256 校验
        msg.progress = 100;
        msg.speed = 0;
        msg.status = ChatMessage.ST_RECEIVING;
        Bus.post(Bus.MESSAGE_UPDATED, msg);

        String actual;
        try {
            actual = Util.sha256(partFile);
        } catch (Throwable t) {
            actual = "";
        }
        boolean hashOk = sha.length() == 64 && actual.length() == 64 && actual.equalsIgnoreCase(sha);
        if (!hashOk) {
            msg.status = ChatMessage.ST_FAILED;
            msg.error = "SHA-256 校验失败";
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            try {
                JSONObject r = new JSONObject();
                r.put("fileId", fileId);
                r.put("ok", false);
                r.put("error", "SHA-256 校验失败");
                r.put("sha256", actual);
                writer.writeJson(Protocol.T_FILE_RESULT, r);
            } catch (Throwable ignored) {
            }
            stateFile.delete();
            return;
        }

        File target = Util.uniqueFile(dir, fileName);
        if (partFile.renameTo(target)) {
            stateFile.delete();
            try {
                JSONObject early = new JSONObject();
                early.put("fileId", fileId);
                early.put("ok", true);
                early.put("sha256", actual);
                writer.writeJson(Protocol.T_FILE_RESULT, early);
            } catch (Throwable ignored) {
            }
            msg.status = ChatMessage.ST_DONE;
            msg.localPath = target.getAbsolutePath();
            msg.sha256 = actual;
            msg.progress = 100;
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            Bus.toast("已接收 " + target.getName());
            transfer.notifyDone("已接收 " + target.getName());
        } else {
            msg.status = ChatMessage.ST_FAILED;
            msg.error = "文件保存失败";
            store.update(msg);
            Bus.post(Bus.MESSAGE_UPDATED, msg);
            try {
                JSONObject r = new JSONObject();
                r.put("fileId", fileId);
                r.put("ok", false);
                r.put("error", "对方保存文件失败");
                writer.writeJson(Protocol.T_FILE_RESULT, r);
            } catch (Throwable ignored) {
            }
        }
    }

    private void reject(Protocol.Writer writer, String fileId, String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("fileId", fileId);
            o.put("reason", reason);
            writer.writeJson(Protocol.T_FILE_REJECT, o);
        } catch (Throwable ignored) {
        }
    }

    private void writeState(File f, long size, int chunkSize, int total, BitSet bits) {
        FileOutputStream out = null;
        try {
            JSONObject o = new JSONObject();
            o.put("size", size);
            o.put("chunkSize", chunkSize);
            o.put("total", total);
            o.put("bits", Protocol.bitsetToBase64(bits));
            out = new FileOutputStream(f);
            out.write(o.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "write resume state failed", t);
        } finally {
            Util.closeQuietly(out);
        }
    }

    private String readSmall(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 256 * 1024)];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } finally {
            Util.closeQuietly(in);
        }
    }
}
