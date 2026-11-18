package com.lanflash.app.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 本地持久化：身份、昵称、信任列表、被拒绝列表。
 */
public class Prefs {

    private static final String FILE = "lanflash_prefs";
    private static final String K_DEVICE_ID = "device_id";
    private static final String K_NICKNAME = "nickname";
    private static final String K_TRUSTED = "trusted_ids";
    private static final String K_DENIED = "denied_ids";
    private static final String K_LAST_PEER = "last_peer_";
    private static final String K_LAN_ENABLED = "lan_enabled";
    private static final String K_AUTO_ACCEPT = "auto_accept_files";
    private static final String K_DOWNLOAD_DIR = "download_dir";
    private static final String K_REMEMBERED = "remembered_peers";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        ensureDeviceId();
    }

    private String ensureDeviceId() {
        String id = sp.getString(K_DEVICE_ID, null);
        if (id == null || id.length() < 8) {
            id = UUID.randomUUID().toString();
            sp.edit().putString(K_DEVICE_ID, id).apply();
        }
        return id;
    }

    public String deviceId() {
        return ensureDeviceId();
    }

    public String shortId() {
        return Util.shortId(deviceId());
    }

    public String nickname() {
        String n = sp.getString(K_NICKNAME, null);
        if (n == null || n.trim().length() == 0) {
            String id = deviceId().replace("-", "");
            n = "设备-" + id.substring(id.length() - 4).toUpperCase();
        }
        return n;
    }

    public void setNickname(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() > 24) n = n.substring(0, 24);
        sp.edit().putString(K_NICKNAME, n).apply();
    }

    public Set<String> trusted() {
        return new HashSet<String>(sp.getStringSet(K_TRUSTED, new HashSet<String>()));
    }

    public boolean isTrusted(String deviceId) {
        return deviceId != null && trusted().contains(deviceId);
    }

    private static final Object TRUST_LOCK = new Object();

    public void trust(String deviceId) {
        if (deviceId == null) return;
        synchronized (TRUST_LOCK) {
            Set<String> s = new HashSet<String>(trusted());
            s.add(deviceId);
            Set<String> d = new HashSet<String>(denied());
            d.remove(deviceId);
            sp.edit().putStringSet(K_TRUSTED, s).putStringSet(K_DENIED, d).apply();
        }
    }

    public Set<String> denied() {
        return new HashSet<String>(sp.getStringSet(K_DENIED, new HashSet<String>()));
    }

    public boolean isDenied(String deviceId) {
        return deviceId != null && denied().contains(deviceId);
    }

    public void deny(String deviceId) {
        if (deviceId == null) return;
        Set<String> s = denied();
        s.add(deviceId);
        sp.edit().putStringSet(K_DENIED, s).apply();
    }

    public void untrust(String deviceId) {
        if (deviceId == null) return;
        Set<String> s = trusted();
        if (s.remove(deviceId)) {
            sp.edit().putStringSet(K_TRUSTED, s).apply();
        }
    }

    public void rememberPeerAddress(String deviceId, String host, int port) {
        if (deviceId == null || host == null) return;
        sp.edit().putString(K_LAST_PEER + deviceId, host + ":" + port).apply();
    }

    public String lastPeerAddress(String deviceId) {
        return sp.getString(K_LAST_PEER + deviceId, null);
    }

    public boolean lanEnabled() {
        return sp.getBoolean(K_LAN_ENABLED, true);
    }

    public void setLanEnabled(boolean v) {
        sp.edit().putBoolean(K_LAN_ENABLED, v).apply();
    }

    public boolean autoAcceptFiles() {
        return sp.getBoolean(K_AUTO_ACCEPT, true);
    }

    public void setAutoAcceptFiles(boolean v) {
        sp.edit().putBoolean(K_AUTO_ACCEPT, v).apply();
    }

    /** 下载目录：app=应用专属目录，public=系统下载目录（仅 API<=28）。 */
    public String downloadDir() {
        return sp.getString(K_DOWNLOAD_DIR, "app");
    }

    public void setDownloadDir(String v) {
        sp.edit().putString(K_DOWNLOAD_DIR, v == null ? "app" : v).apply();
    }

    public String rememberedPeers() {
        return sp.getString(K_REMEMBERED, "");
    }

    public void setRememberedPeers(String json) {
        sp.edit().putString(K_REMEMBERED, json == null ? "" : json).apply();
    }
}
