package com.lanflash.app.ui;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lanflash.app.R;

/**
 * UI 工具：Toast、对话框、底部菜单。
 */
public final class Ui {

    private Ui() {}

    public static int dp(Context ctx, float v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    public static void toast(Context ctx, String msg) {
        if (ctx == null || msg == null) return;
        Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    public static void pressable(final View v) {
        v.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.setAlpha(0.7f);
                        break;
                    case android.view.MotionEvent.ACTION_CANCEL:
                    case android.view.MotionEvent.ACTION_UP:
                        view.setAlpha(1f);
                        break;
                }
                return false;
            }
        });
    }

    public static void alert(Context ctx, String title, String msg, String ok, final Runnable onOk) {
        if (ctx == null) return;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
        b.setTitle(title);
        b.setMessage(msg);
        b.setPositiveButton(ok, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                if (onOk != null) onOk.run();
            }
        });
        try {
            b.show();
        } catch (Throwable ignored) {
        }
    }

    public interface OnClick {
        void onClick();
    }

    /** 底部操作菜单。 */
    public static void sheet(Context ctx, String[] items, final OnClick[] clicks) {
        if (ctx == null || items == null) return;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
        b.setItems(items, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                if (which >= 0 && which < clicks.length && clicks[which] != null) {
                    clicks[which].onClick();
                }
            }
        });
        try {
            b.show();
        } catch (Throwable ignored) {
        }
    }

    /** 输入对话框。 */
    public static void prompt(Context ctx, String title, String hint, String initial,
                              final android.widget.TextView.OnEditorActionListener done) {
        if (ctx == null) return;
        final android.widget.EditText et = new android.widget.EditText(ctx);
        et.setHint(hint);
        et.setText(initial);
        et.setSingleLine(true);
        int pad = dp(ctx, 16);
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, dp(ctx, 8), pad, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
        b.setTitle(title);
        b.setView(wrap);
        b.setPositiveButton("确定", null);
        final android.app.AlertDialog dlg = b.create();
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface d) {
                android.widget.Button btn = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (done != null) {
                            done.onEditorAction(et, android.view.inputmethod.EditorInfo.IME_ACTION_DONE,
                                    null);
                        }
                        dlg.dismiss();
                    }
                });
            }
        });
        try {
            dlg.show();
        } catch (Throwable ignored) {
        }
    }

    public static void copyText(Context ctx, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text));
            toast(ctx, ctx.getString(R.string.copied));
        } catch (Throwable t) {
            toast(ctx, "复制失败");
        }
    }
}
