package com.lanflash.app.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.lanflash.app.R;
import com.lanflash.app.core.AppCore;
import com.lanflash.app.core.Bus;
import com.lanflash.app.core.Const;
import com.lanflash.app.core.Util;
import com.lanflash.app.model.ChatMessage;
import com.lanflash.app.model.Peer;
import com.lanflash.app.net.NetService;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * 单设备聊天页：文本 + 文件 + 传输状态。
 */
public class ChatActivity extends AppCompatActivity implements Bus.Listener {

    public static final String EXTRA_PEER_ID = "peer_id";

    private static final int REQ_PICK_FILE = 4201;
    private static final int REQ_PICK_IMAGE = 4202;
    private static final int REQ_STORAGE = 4203;

    private AppCore core;
    private String peerId;
    private Peer peer;
    private MessageAdapter adapter;
    private ListView listView;
    private EditText input;
    private TextView status;
    private TextView tvPeerName;

    private String pendingMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        core = AppCore.get(this);
        peerId = getIntent().getStringExtra(EXTRA_PEER_ID);
        peer = core.peers.get(peerId);
        if (peer == null) {
            String addr = core.prefs.lastPeerAddress(peerId);
            String host = "";
            int port = Const.DEFAULT_TCP_PORT;
            if (addr != null && addr.contains(":")) {
                int i = addr.lastIndexOf(':');
                host = addr.substring(0, i);
                try {
                    port = Integer.parseInt(addr.substring(i + 1));
                } catch (Throwable ignored) {
                }
            }
            if (peerId != null) {
                peer = new Peer(peerId, null, host, port, Peer.SRC_MANUAL);
            }
        }

        status = (TextView) findViewById(R.id.tv_status);
        tvPeerName = (TextView) findViewById(R.id.tv_peer_name);
        input = (EditText) findViewById(R.id.et_input);
        listView = (ListView) findViewById(R.id.list_messages);
        adapter = new MessageAdapter(this);
        listView.setAdapter(adapter);
        if (peer != null) {
            tvPeerName.setText(peer.displayName());
            adapter.setData(core.messages.load(peer.deviceId));
        }

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_send).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendText();
            }
        });
        findViewById(R.id.btn_attach).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAttachSheet();
            }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoreSheet();
            }
        });

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Object o = adapter.getItem(position);
                if (o instanceof ChatMessage && ((ChatMessage) o).isFile()) {
                    openFile((ChatMessage) o);
                }
            }
        });
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Object o = adapter.getItem(position);
                if (o instanceof ChatMessage) showMessageMenu((ChatMessage) o);
                return true;
            }
        });

        updateStatus();
        scrollToBottom();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bus.register(this);
        updateStatus();
        if (peer != null && adapter != null) {
            adapter.setData(core.messages.load(peer.deviceId));
            scrollToBottom();
        }
    }

    @Override
    protected void onPause() {
        Bus.unregister(this);
        super.onPause();
    }

    private void updateStatus() {
        if (status == null) return;
        boolean online = peer != null && peer.isOnline(System.currentTimeMillis());
        status.setText((online ? getString(R.string.status_online) : getString(R.string.status_offline))
                + " · " + (peer == null ? "--------" : peer.shortId())
                + (peer != null && peer.trusted ? "" : " · " + getString(R.string.untrusted)));
        if (peer != null && tvPeerName != null) {
            tvPeerName.setText(peer.displayName());
        }
    }

    private void scrollToBottom() {
        if (adapter.getCount() == 0) return;
        listView.setSelection(adapter.getCount() - 1);
    }

    // ---------------- 文本 ----------------}

    private void sendText() {
        String text = input.getText().toString();
        if (TextUtils.isEmpty(text) || peer == null) return;
        if (text.length() > Const.MAX_TEXT_CHARS) {
            Ui.toast(this, "消息过长，最多 " + Const.MAX_TEXT_CHARS + " 字");
            return;
        }
        ChatMessage m = new ChatMessage();
        m.peerDeviceId = peer.deviceId;
        m.direction = ChatMessage.DIR_OUT;
        m.type = ChatMessage.TYPE_TEXT;
        m.text = text;
        m.status = ChatMessage.ST_SENDING;
        core.messages.append(m);
        adapter.add(m);
        input.setText("");
        scrollToBottom();

        NetService s = core.service();
        if (s == null) {
            m.status = ChatMessage.ST_FAILED;
            m.error = "服务未启动";
            core.messages.update(m);
            adapter.update(m);
            return;
        }
        s.sendText(peer, m);
    }

    // ---------------- 文件 ----------------}

    private void showAttachSheet() {
        Ui.sheet(this, new String[]{
                getString(R.string.pick_file),
                getString(R.string.pick_image)
        }, new Ui.OnClick[]{
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        pickWithPermission("*/*", REQ_PICK_FILE);
                    }
                },
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        pickWithPermission("image/*", REQ_PICK_IMAGE);
                    }
                }
        });
    }

    private void showMoreSheet() {
        Ui.sheet(this, new String[]{
                "清空聊天记录"
        }, new Ui.OnClick[]{
                new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        if (peer != null) {
                            core.messages.clear(peer.deviceId);
                            adapter.setData(new java.util.ArrayList<ChatMessage>());
                        }
                    }
                }
        });
    }

    private void pickWithPermission(String mime, int req) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingMime = mime;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        launchPicker(mime, req);
    }

    private void launchPicker(String mime, int req) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, req);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType(mime);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, getString(R.string.pick_file)), req);
        } catch (ActivityNotFoundException e) {
            Ui.alert(this, getString(R.string.pick_file), "系统没有文件选择器", getString(R.string.know), null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            String mime = pendingMime == null ? "*/*" : pendingMime;
            int req = "image/*".equals(mime) ? REQ_PICK_IMAGE : REQ_PICK_FILE;
            pendingMime = null;
            launchPicker(mime, req);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode != REQ_PICK_FILE && requestCode != REQ_PICK_IMAGE) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String name = null;
        long size = 0;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (name == null) name = "file-" + System.currentTimeMillis();
        if (size <= 0) {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in != null) {
                    size = in.available();
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
        sendFile(uri, name, size);
    }

    private void sendFile(Uri uri, String name, long size) {
        if (peer == null) return;
        if (size <= 0) {
            Ui.alert(this, getString(R.string.pick_file),
                    "无法读取文件大小，请换一个文件重试", getString(R.string.know), null);
            return;
        }
        ChatMessage m = new ChatMessage();
        m.peerDeviceId = peer.deviceId;
        m.direction = ChatMessage.DIR_OUT;
        m.type = ChatMessage.TYPE_FILE;
        m.fileId = UUID.randomUUID().toString();
        m.fileName = Util.safeFileName(name);
        m.fileSize = size;
        m.status = ChatMessage.ST_SENDING;
        core.messages.append(m);
        adapter.add(m);
        scrollToBottom();

        NetService s = core.service();
        if (s == null) {
            m.status = ChatMessage.ST_FAILED;
            m.error = "服务未启动";
            core.messages.update(m);
            adapter.update(m);
            return;
        }
        s.sendFile(peer, m, uri, null);
    }

    // ---------------- 文件打开与消息操作 ----------------}

    private void openFile(ChatMessage m) {
        if (m == null || m.localPath == null || m.localPath.length() == 0) return;
        File f = new File(m.localPath);
        if (!f.isFile()) {
            Ui.toast(this, "文件不存在");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, guessMime(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.open_file)));
        } catch (Throwable t) {
            Ui.toast(this, "没有可打开该文件的应用");
        }
    }

    private String guessMime(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private void showMessageMenu(final ChatMessage m) {
        java.util.List<String> items = new java.util.ArrayList<String>();
        java.util.List<Ui.OnClick> clicks = new java.util.ArrayList<Ui.OnClick>();
        if (m.isFile()) {
            items.add(getString(R.string.open_file));
            clicks.add(new Ui.OnClick() {
                @Override
                public void onClick() {
                    openFile(m);
                }
            });
            if (ChatMessage.ST_SENDING.equals(m.status) || ChatMessage.ST_RECEIVING.equals(m.status)) {
                items.add(getString(R.string.cancel_transfer));
                clicks.add(new Ui.OnClick() {
                    @Override
                    public void onClick() {
                        NetService s = core.service();
                        if (s != null && m.fileId != null) s.transfers().cancel(m.fileId);
                    }
                });
            }
        }
        items.add(getString(R.string.delete_message));
        clicks.add(new Ui.OnClick() {
            @Override
            public void onClick() {
                core.messages.remove(m.peerDeviceId, m.msgId);
                if (peer != null) adapter.setData(core.messages.load(peer.deviceId));
            }
        });
        Ui.sheet(this, items.toArray(new String[items.size()]),
                clicks.toArray(new Ui.OnClick[clicks.size()]));
    }

    // ---------------- 事件 ----------------}

    @Override
    public void onEvent(String type, Object payload) {
        if (Bus.MESSAGE_NEW.equals(type)) {
            ChatMessage m = (ChatMessage) payload;
            if (peer != null && peer.deviceId.equals(m.peerDeviceId)) {
                adapter.add(m);
                scrollToBottom();
            }
        } else if (Bus.MESSAGE_UPDATED.equals(type)) {
            ChatMessage m = (ChatMessage) payload;
            if (peer != null && peer.deviceId.equals(m.peerDeviceId)) {
                adapter.update(m);
            }
        } else if (Bus.TOAST.equals(type)) {
            Ui.toast(this, String.valueOf(payload));
        } else if (Bus.TRUST_REQUEST.equals(type)) {
            showTrustDialog((NetService.TrustRequest) payload);
        } else if (Bus.FILE_REQUEST.equals(type)) {
            showFileDialog((NetService.FileRequest) payload);
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
