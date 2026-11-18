package com.lanflash.app.net;

import com.lanflash.app.core.Prefs;
import com.lanflash.app.core.Util;
import com.lanflash.app.model.Peer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邻居注册表：NSD / 组播 / 手动三条路径的统一收敛点，以 deviceId 去重。
 */
public class PeerRegistry {

    private static final String PEER_SHORT_PREFIX = "short-";
    private static final int MAX_REMEMBERED = 30;

    private final Map<String, Peer> peers = new LinkedHashMap<String, Peer>();
    private final Map<String, Long> muted = new HashMap<String, Long>();
    private final Prefs prefs;
    private final String selfId;
    private long lastPersistAt;

    public PeerRegistry(Prefs prefs) {
        this.prefs = prefs;
        this.selfId = prefs.deviceId();
        loadRemembered();
    }

    private void loadRemembered() {
        String json = prefs.rememberedPeers();
        if (json == null || json.length() == 0) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", "");
                String host = o.optString("host", "");
                int port = o.optInt("port", 0);
                if (id.length() == 0 || host.length() == 0 || port <= 0) continue;
                if (id.equals(selfId)) continue;
                Peer p = new Peer(id, o.optString("name", ""), host, port, Peer.SRC_MEMORY);
                p.remembered = true;
                p.lastSeen = o.optLong("seen", 0);
                p.trusted = prefs.isTrusted(id);
                peers.put(id, p);
            }
        } catch (Throwable ignored) {
        }
    }

    private void persistLater() {
        long now = System.currentTimeMillis();
        if (now - lastPersistAt < 5000) return;
        lastPersistAt = now;
        persistNow();
    }

    public synchronized void persistNow() {
        try {
            List<Peer> all = new ArrayList<Peer>(peers.values());
            Collections.sort(all, new Comparator<Peer>() {
                @Override
                public int compare(Peer a, Peer b) {
                    if (a.lastSeen == b.lastSeen) return 0;
                    return a.lastSeen > b.lastSeen ? -1 : 1;
                }
            });
            JSONArray arr = new JSONArray();
            for (int i = 0; i < all.size() && i < MAX_REMEMBERED; i++) {
                Peer p = all.get(i);
                if (p.deviceId == null || p.host == null || p.port <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("id", p.deviceId);
                o.put("name", p.nickname == null ? "" : p.nickname);
                o.put("host", p.host);
                o.put("port", p.port);
                o.put("seen", p.lastSeen);
                arr.put(o);
            }
            prefs.setRememberedPeers(arr.toString());
        } catch (Throwable ignored) {
        }
    }

    public synchronized boolean upsert(String deviceId, String nickname, String host, int port, String source) {
        if (deviceId == null || host == null || port <= 0) return false;
        if (deviceId.equals(selfId)) return false;
        Long mutedUntil = muted.get(deviceId);
        if (mutedUntil != null) {
            if (mutedUntil > System.currentTimeMillis()) return false;
            muted.remove(deviceId);
        }

        if (deviceId.startsWith(PEER_SHORT_PREFIX)) {
            String sid = deviceId.substring(PEER_SHORT_PREFIX.length());
            for (Peer exist : peers.values()) {
                if (!exist.deviceId.startsWith(PEER_SHORT_PREFIX)
                        && Util.shortId(exist.deviceId).equalsIgnoreCase(sid)) {
                    return false;
                }
            }
        } else {
            peers.remove(PEER_SHORT_PREFIX + Util.shortId(deviceId));
        }

        Peer p = peers.get(deviceId);
        boolean changed = false;
        if (p == null) {
            p = new Peer(deviceId, nickname, host, port, source);
            p.trusted = prefs.isTrusted(deviceId);
            p.remembered = true;
            peers.put(deviceId, p);
            changed = true;
        } else {
            if (nickname != null && nickname.length() > 0 && !nickname.equals(p.nickname)) {
                p.nickname = nickname;
                changed = true;
            }
            if (!host.equals(p.host) || port != p.port) {
                p.host = host;
                p.port = port;
                p.remembered = true;
                changed = true;
            }
            p.lastSeen = System.currentTimeMillis();
            p.source = source;
            boolean trusted = prefs.isTrusted(deviceId);
            if (trusted != p.trusted) {
                p.trusted = trusted;
                changed = true;
            }
        }
        if (changed) persistLater();
        return changed;
    }

    public synchronized Peer get(String deviceId) {
        return peers.get(deviceId);
    }

    public synchronized void touch(String deviceId, String host, int port) {
        Peer p = peers.get(deviceId);
        if (p == null) {
            if (host != null && port > 0) {
                upsert(deviceId, null, host, port, Peer.SRC_MANUAL);
            }
            return;
        }
        p.lastSeen = System.currentTimeMillis();
        if (host != null) p.host = host;
        if (port > 0) p.port = port;
    }

    public synchronized boolean removeExpired() {
        long now = System.currentTimeMillis();
        List<String> dead = new ArrayList<String>();
        for (Peer p : peers.values()) {
            if (p.isExpired(now) && !p.remembered) dead.add(p.deviceId);
        }
        for (String id : dead) peers.remove(id);
        return !dead.isEmpty();
    }

    public synchronized void remove(String deviceId) {
        peers.remove(deviceId);
        if (deviceId != null) muted.put(deviceId, System.currentTimeMillis() + 30L * 60 * 1000);
        persistNow();
    }

    public synchronized List<Peer> list() {
        List<Peer> list = new ArrayList<Peer>(peers.values());
        final long now = System.currentTimeMillis();
        Collections.sort(list, new Comparator<Peer>() {
            @Override
            public int compare(Peer a, Peer b) {
                boolean ao = a.isOnline(now), bo = b.isOnline(now);
                if (ao != bo) return ao ? -1 : 1;
                if (a.trusted != b.trusted) return a.trusted ? -1 : 1;
                if (a.lastSeen == b.lastSeen) return 0;
                return a.lastSeen > b.lastSeen ? -1 : 1;
            }
        });
        return list;
    }

    public synchronized int size() {
        return peers.size();
    }

    public synchronized boolean hasOnlinePeer() {
        long now = System.currentTimeMillis();
        for (Peer p : peers.values()) {
            if (p.isOnline(now)) return true;
        }
        return false;
    }

    public String selfId() {
        return selfId;
    }

    public String selfShortId() {
        return Util.shortId(selfId);
    }
}
