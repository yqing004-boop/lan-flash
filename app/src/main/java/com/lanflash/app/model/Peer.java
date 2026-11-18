package com.lanflash.app.model;

import com.lanflash.app.core.Const;
import com.lanflash.app.core.Util;

/**
 * 一个局域网邻居。
 */
public class Peer {

    public static final String SRC_NSD = "nsd";
    public static final String SRC_MULTICAST = "multicast";
    public static final String SRC_MANUAL = "manual";
    public static final String SRC_MEMORY = "memory";

    public final String deviceId;
    public volatile String nickname;
    public volatile String host;
    public volatile int port;
    public volatile long lastSeen;
    public volatile String source;
    public volatile boolean trusted;
    public volatile int protoVersion = 1;
    public volatile boolean remembered;

    public Peer(String deviceId, String nickname, String host, int port, String source) {
        this.deviceId = deviceId;
        this.nickname = nickname;
        this.host = host;
        this.port = port;
        this.source = source;
        this.lastSeen = System.currentTimeMillis();
    }

    public String shortId() {
        return Util.shortId(deviceId);
    }

    public String displayName() {
        if (nickname == null || nickname.trim().length() == 0) return shortId();
        return nickname;
    }

    public boolean isExpired(long now) {
        return now - lastSeen > Const.PEER_EXPIRE_MS;
    }

    public boolean isOnline(long now) {
        return now - lastSeen <= Const.PEER_EXPIRE_MS;
    }

    public String address() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Peer)) return false;
        return deviceId.equals(((Peer) o).deviceId);
    }

    @Override
    public int hashCode() {
        return deviceId.hashCode();
    }
}
