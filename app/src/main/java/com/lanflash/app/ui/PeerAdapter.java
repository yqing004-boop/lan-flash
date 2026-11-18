package com.lanflash.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.lanflash.app.R;
import com.lanflash.app.model.Peer;

import java.util.ArrayList;
import java.util.List;

public class PeerAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private List<Peer> data = new ArrayList<Peer>();

    public PeerAdapter(Context ctx) {
        this.inflater = LayoutInflater.from(ctx);
    }

    public void setData(List<Peer> list) {
        data = list == null ? new ArrayList<Peer>() : list;
        notifyDataSetChanged();
    }

    public Peer getItemAt(int position) {
        if (position < 0 || position >= data.size()) return null;
        return data.get(position);
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
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_peer, parent, false);
            h = new ViewHolder();
            h.name = (TextView) convertView.findViewById(R.id.tv_peer_name);
            h.id = (TextView) convertView.findViewById(R.id.tv_peer_id);
            h.badge = (TextView) convertView.findViewById(R.id.tv_badge);
            convertView.setTag(h);
        } else {
            h = (ViewHolder) convertView.getTag();
        }
        Peer p = getItemAt(position);
        if (p == null) return convertView;
        h.name.setText(p.displayName());
        long now = System.currentTimeMillis();
        boolean online = p.isOnline(now);
        h.name.setAlpha(online ? 1f : 0.5f);
        h.id.setText("ID " + p.shortId() + " · " + p.address());
        if (online) {
            h.badge.setText(p.trusted ? "在线 · 已信任" : "在线 · 未信任");
        } else {
            h.badge.setText(p.trusted ? "离线 · 已信任" : "离线");
        }
        return convertView;
    }

    private static class ViewHolder {
        TextView name;
        TextView id;
        TextView badge;
    }
}
