package com.lanflash.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.lanflash.app.R;
import com.lanflash.app.core.Util;
import com.lanflash.app.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public class MessageAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private List<ChatMessage> data = new ArrayList<ChatMessage>();
    private Context ctx;

    public MessageAdapter(Context ctx) {
        this.ctx = ctx;
        this.inflater = LayoutInflater.from(ctx);
    }

    public void setData(List<ChatMessage> list) {
        data = list == null ? new ArrayList<ChatMessage>() : list;
        notifyDataSetChanged();
    }

    public void add(ChatMessage m) {
        data.add(m);
        notifyDataSetChanged();
    }

    public void update(ChatMessage m) {
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).msgId.equals(m.msgId)) {
                data.set(i, m);
                notifyDataSetChanged();
                return;
            }
        }
        data.add(m);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage m = (ChatMessage) getItem(position);
        return m.isFile() ? 1 : 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final ChatMessage m = (ChatMessage) getItem(position);
        boolean out = m.isOutgoing();
        int colorText = ctx.getResources().getColor(R.color.text);

        if (m.isFile()) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_msg_file, parent, false);
            }
            TextView name = (TextView) convertView.findViewById(R.id.tv_file_name);
            TextView meta = (TextView) convertView.findViewById(R.id.tv_file_meta);
            ProgressBar pb = (ProgressBar) convertView.findViewById(R.id.pb_file);
            LinearLayout card = (LinearLayout) convertView.findViewById(R.id.file_card_root);
            if (card == null) card = (LinearLayout) convertView;

            name.setText(m.fileName == null ? "file" : m.fileName);
            StringBuilder sb = new StringBuilder();
            sb.append(Util.humanSize(m.fileSize));
            if (ChatMessage.ST_RECEIVING.equals(m.status) || ChatMessage.ST_SENDING.equals(m.status)) {
                sb.append(" · ").append(m.progress < 0 ? "计算校验中…" : m.progress + "%");
                if (m.speed > 0) sb.append(" · ").append(Util.humanSpeed(m.speed));
            } else if (ChatMessage.ST_DONE.equals(m.status) || ChatMessage.ST_SENT.equals(m.status)) {
                sb.append(" · 已完成");
            } else if (ChatMessage.ST_FAILED.equals(m.status)) {
                sb.append(" · 失败：").append(m.error == null ? "未知错误" : m.error);
            } else if (ChatMessage.ST_CANCELLED.equals(m.status)) {
                sb.append(" · 已取消");
            } else {
                sb.append(" · 等待中");
            }
            meta.setText(sb.toString());
            int p = m.progress;
            if (p < 0) p = 0;
            if (p > 100) p = 100;
            pb.setProgress(p);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(ctx, 260), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = out ? Gravity.RIGHT : Gravity.LEFT;
            card.setLayoutParams(lp);
            return convertView;
        }

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_msg_text, parent, false);
        }
        TextView tv = (TextView) convertView.findViewById(R.id.tv_text);
        tv.setText(m.text);
        tv.setTextColor(ctx.getResources().getColor(out ? R.color.bubble_out_text : R.color.bubble_in_text));
        tv.setBackgroundResource(out ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = out ? Gravity.RIGHT : Gravity.LEFT;
        tv.setLayoutParams(lp);
        return convertView;
    }
}
