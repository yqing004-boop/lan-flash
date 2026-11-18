package com.lanflash.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.lanflash.app.R;
import com.lanflash.app.core.AppCore;
import com.lanflash.app.core.Bus;
import com.lanflash.app.core.Const;
import com.lanflash.app.core.Util;
import com.lanflash.app.model.Peer;
import com.lanflash.app.net.NetService;

import java.util.List;

/**
 * 主界面：本机信息 + 设备列表 + 手动添加。
 */
public class MainActivity extends AppCompatActivity implements Bus.Listener {

    private static final int REQ_NOTIF = 4401;

    private AppCore core;
    private PeerAdapter adapter;
    private ListView listView;
    private TextView tvEmpty;
    private TextView tvCount;
    private TextView tvOwnId;
    private TextView tvNickname;
    private TextView tvLanState;
    private Switch swLan;
    private boolean updatingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        core = AppCore.get(this);

        bindViews();
        setupLanPage();
        ensureService();
        maybeRequestNotifPermission();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bus.register(this);
        refresh();
    }

    @Override
    protected void onPause() {
        Bus.unregister(this);
        super.onPause();
    }

    private void bindViews() {
        tvOwnId = (TextView) findViewById(R.id.tv_own_id);
        tvNickname = (TextView) findViewById(R.id.tv_nickname);
        tvLanState = (TextView) findViewById(R.id.tv_lan_state);
        swLan = (Switch) findViewById(R.id.sw_lan);
        tvCount = (TextView) findViewById(R.id.tv_count);
        listView = (ListView) findViewById(R.id.list_peers);
        tvEmpty = (TextView) findViewById(R.id.tv_empty);
        listView.setEmptyView(tvEmpty);
    }

    private void setupLanPage() {
        adapter = new PeerAdapter(this);
        listView.setAdapter(adapter);

        tvNickname.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editNickname();
            }
        });

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NetService s = core.service();
                if (s != null) s.restartDiscovery();
                refresh();
                Ui.toast(MainActivity.this, "正在重新搜索附近设备…");
            }
        });

        findViewById(R.id.btn_manual).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualAdd();
            }
        });

        swLan.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitch) return;
                core.prefs.setLanEnabled(isChecked);
                NetService s = core.service();
                if (s != null) {
                    if (isChecked) {
                        s.startLan();
                    } else {
                        s.stopLan();
                    }
                }
                refresh();
                Ui.toast(MainActivity.this, isChecked ? "局域网传输已开启" : "局域网传输已关闭");
            }
        });

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Peer p = adapter.getItemAt(position);
                if (p == null) return;
                Intent i = new Intent(MainActivity.this, ChatActivity.class);
                i.putExtra(ChatActivity.EXTRA_PEER_ID, p.deviceId);
                startActivity(i);
            }
        });
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                final Peer p = adapter.getItemAt(position);
                if (p != null) showPeerMenu(p);
                return true;
            }
        });
    }

    private void editNickname() {
        Ui.prompt(this, "修改昵称", "输入设备昵称", core.prefs.nickname(),
                new TextView.OnEditorActionListener() {
                    @Override
                    public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                        String s = v.getText().toString().trim();
                        if (s.length() > 0) {
                            core.prefs.setNickname(s);
                            refresh();
                            NetService svc = core.service();
                            if (svc != null) svc.restartDiscovery();
                            Ui.toast(MainActivity.this, "昵称已更新");
                        }
                        return true;
                    }
                });
    }

    private void manualAdd() {
        Ui.prompt(this, "手动添加", "IP:端口，例如 192.168.1.5:45960", "",
                new TextView.OnEditorActionListener() {
                    @Override
                    public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                        String s = v.getText().toString().trim();
                        if (s.length() == 0) return true;
                        String host = s;
                        int port = Const.DEFAULT_TCP_PORT;
                        int idx = s.lastIndexOf(':');
                        if (idx > 0) {
                            host = s.substring(0, idx).trim();
                            try {
                                port = Integer.parseInt(s.substring(idx + 1).trim());
                            } catch (Throwable ignored) {
                            }
                        }
                        NetService svc = core.service();
                        if (svc != null) {
                            Ui.toast(MainActivity.this, "正在连接 " + host + ":" + port + "…");
                            svc.connectManual(host, port);
                        }
                        return true;
                    }
                });
    }

    private void showPeerMenu(final Peer p) {
        Ui.sheet(this, new String[]{
                "打开聊天",
                p.trusted ? "取消信任" : "信任该设备",
                "移除设备"
        }, new Ui.OnClick[]{
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        Intent i = new Intent(MainActivity.this, ChatActivity.class);
                        i.putExtra(ChatActivity.EXTRA_PEER_ID, p.deviceId);
                        startActivity(i);
                    }
                },
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        if (p.trusted) {
                            core.prefs.untrust(p.deviceId);
                            p.trusted = false;
                        } else {
                            core.prefs.trust(p.deviceId);
                            p.trusted = true;
                        }
                        refresh();
                    }
                },
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        core.peers.remove(p.deviceId);
                        refresh();
                    }
                }
        });
    }

    private void refresh() {
        if (core == null || tvNickname == null) return;
        tvNickname.setText(core.prefs.nickname());
        tvOwnId.setText(getString(R.string.own_id, core.prefs.shortId()));
        boolean lan = core.prefs.lanEnabled();
        updatingSwitch = true;
        swLan.setChecked(lan);
        updatingSwitch = false;
        NetService s = core.service();
        int port = s == null ? Const.DEFAULT_TCP_PORT : s.getPort();
        String ip = Util.localIpv4(this);
        if (lan) {
            tvLanState.setText(getString(R.string.lan_state_on, port)
                    + (ip == null ? "" : " · 本机 " + ip));
        } else {
            tvLanState.setText(getString(R.string.lan_state_off));
        }
        List<Peer> list = core.peers.list();
        adapter.setData(list);
        int online = 0;
        long now = System.currentTimeMillis();
        for (Peer p : list) if (p.isOnline(now)) online++;
        tvCount.setText(getString(R.string.peer_count, online));
    }

    private void ensureService() {
        Intent i = new Intent(this, NetService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onEvent(String type, Object payload) {
        if (Bus.PEERS_CHANGED.equals(type) || Bus.PEER_UPDATED.equals(type)) {
            refresh();
        } else if (Bus.TOAST.equals(type)) {
            Ui.toast(this, String.valueOf(payload));
        } else if (Bus.TRUST_REQUEST.equals(type)) {
            showTrustDialog((NetService.TrustRequest) payload);
        } else if (Bus.FILE_REQUEST.equals(type)) {
            showFileDialog((NetService.FileRequest) payload);
        } else if (Bus.MANUAL_RESULT.equals(type)) {
            String r = String.valueOf(payload);
            if (r.startsWith("ok")) {
                refresh();
            } else {
                Ui.toast(this, "添加失败：" + r.substring(r.indexOf(':') + 1));
            }
        }
    }

    private void showTrustDialog(final NetService.TrustRequest req) {
        if (req == null) return;
        String name = req.nickname == null || req.nickname.length() == 0
                ? Util.shortId(req.deviceId) : req.nickname;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle(R.string.trust_title);
        b.setMessage(getString(R.string.trust_msg, name, req.host));
        b.setPositiveButton(R.string.allow, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                NetService s = core.service();
                if (s != null) s.answerTrust(req.deviceId, true);
            }
        });
        b.setNegativeButton(R.string.reject, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                NetService s = core.service();
                if (s != null) s.answerTrust(req.deviceId, false);
            }
        });
        b.setCancelable(false);
        try {
            b.show();
        } catch (Throwable ignored) {
        }
    }

    private void showFileDialog(final NetService.FileRequest req) {
        if (req == null) return;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle(R.string.accept_file_title);
        b.setMessage(getString(R.string.file_from, req.fileName) + "\n"
                + getString(R.string.file_size, Util.humanSize(req.size)));
        b.setPositiveButton(R.string.allow, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                NetService s = core.service();
                if (s != null) s.answerFileAccept(req.deviceId, true);
            }
        });
        b.setNegativeButton(R.string.reject, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                NetService s = core.service();
                if (s != null) s.answerFileAccept(req.deviceId, false);
            }
        });
        b.setCancelable(false);
        try {
            b.show();
        } catch (Throwable ignored) {
        }
    }
}
