package com.lanflash.app.core;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * 下载目录策略：API 29+ 只能写应用专属目录。
 */
public final class StorageUtil {

    private StorageUtil() {}

    public static File downloadDir(Context ctx) {
        Prefs prefs = AppCore.get(ctx).prefs;
        if ("public".equals(prefs.downloadDir()) && Build.VERSION.SDK_INT <= 28) {
            File d = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "LanFlash");
            if (!d.exists()) d.mkdirs();
            if (d.isDirectory()) return d;
        }
        File d = ctx.getExternalFilesDir("LanFlash");
        if (d == null) d = new File(ctx.getFilesDir(), "LanFlash");
        if (!d.exists()) d.mkdirs();
        return d;
    }
}
