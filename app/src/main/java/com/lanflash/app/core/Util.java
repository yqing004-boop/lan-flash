package com.lanflash.app.core;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;

/**
 * 纯工具方法集合（API 19 可用，无 java.time / Stream）。
 */
public final class Util {

    private Util() {}

    public static String shortId(String deviceId) {
        if (deviceId == null || deviceId.length() < 8) return "--------";
        String hex = deviceId.replace("-", "").toUpperCase(Locale.US);
        return hex.substring(0, 8);
    }

    public static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /** 流式计算 SHA-256，内存恒定 8KB。 */
    public static String sha256(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static String sha256(File file) throws Exception {
        if (file == null || !file.isFile()) return "";
        FileInputStream in = new FileInputStream(file);
        try {
            return sha256(in);
        } finally {
            closeQuietly(in);
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.1f KB", v);
        v /= 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        v /= 1024.0;
        return String.format(Locale.US, "%.2f GB", v);
    }

    public static String humanSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        return humanSize(bytesPerSec) + "/s";
    }

    public static String formatTime(long ts) {
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        return f.format(new Date(ts));
    }

    /** 本机局域网 IPv4（优先 Wi-Fi），无网络时返回 null。 */
    public static String localIpv4(Context ctx) {
        if (ctx != null) {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    android.net.DhcpInfo d = wm.getDhcpInfo();
                    if (d != null && d.ipAddress != 0) {
                        int ip = d.ipAddress;
                        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                                + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return localIpv4();
    }

    public static String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            String fallback = null;
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName();
                boolean wlan = name != null && name.startsWith("wlan");
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                for (InetAddress addr : Collections.list(addrs)) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    if (wlan) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 清洗文件名，防路径穿越与非法字符。 */
    public static String safeFileName(String name) {
        if (name == null || name.trim().length() == 0) return "file";
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (base.length() == 0) base = "file";
        if (".".equals(base) || "..".equals(base)) base = "file";
        if (base.length() > 120) {
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, 100) + base.substring(dot);
            } else {
                base = base.substring(0, 120);
            }
        }
        return base;
    }

    /** 重名文件自动加 (1)(2)… */
    public static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File cand = new File(dir, stem + "(" + i + ")" + ext);
            if (!cand.exists()) return cand;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    public static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    public static String safe(String s) {
        return s == null ? "" : s;
    }

    public static boolean hasPermission(Context ctx, String permission) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }
}
