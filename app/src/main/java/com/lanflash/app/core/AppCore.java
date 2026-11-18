package com.lanflash.app.core;

import android.content.Context;

import com.lanflash.app.model.ChatMessage;
import com.lanflash.app.model.MessageStore;
import com.lanflash.app.net.NetService;
import com.lanflash.app.net.PeerRegistry;

/**
 * 进程级单例：把不依赖 Service 生命周期的数据集中在一处。
 */
public final class AppCore {

    private static AppCore instance;

    public final Prefs prefs;
    public final MessageStore messages;
    public final PeerRegistry peers;

    private NetService service;

    private AppCore(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.prefs = new Prefs(app);
        this.messages = new MessageStore(app);
        this.peers = new PeerRegistry(prefs);
        new Thread(new Runnable() {
            @Override
            public void run() {
                recoverStaleTransfers();
            }
        }, "lanflash-recover").start();
    }

    /** 进程被杀后，处于 sending/receiving 的文件消息统一标成失败。 */
    private void recoverStaleTransfers() {
        try {
            for (String pid : messages.peerIds()) {
                java.util.List<ChatMessage> list = messages.load(pid);
                for (ChatMessage m : list) {
                    if (ChatMessage.TYPE_FILE.equals(m.type)
                            && (ChatMessage.ST_SENDING.equals(m.status)
                            || ChatMessage.ST_RECEIVING.equals(m.status)
                            || ChatMessage.ST_PAUSED.equals(m.status))) {
                        m.status = ChatMessage.ST_FAILED;
                        m.error = "传输中断（App 已重启）";
                        m.speed = 0;
                        messages.update(m);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized AppCore get(Context ctx) {
        if (instance == null) instance = new AppCore(ctx);
        return instance;
    }

    public synchronized void attachService(NetService s) {
        this.service = s;
    }

    public synchronized NetService service() {
        return service;
    }
}
