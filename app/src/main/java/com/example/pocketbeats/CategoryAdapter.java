package com.example.pocketbeats;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class CategoryAdapter extends ArrayAdapter<String> {

    private final LayoutInflater inflater;
    private List<Integer> counts;

    public CategoryAdapter(Context context, List<String> names, List<Integer> counts) {
        super(context, R.layout.item_category, names);
        this.inflater = LayoutInflater.from(context);
        this.counts = counts;
    }

    public void setCounts(List<Integer> counts) {
        this.counts = counts;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_category, parent, false);
            holder = new ViewHolder();
            holder.name = (TextView) convertView.findViewById(R.id.categoryName);
            holder.count = (TextView) convertView.findViewById(R.id.categoryCount);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        String name = getItem(position);
        holder.name.setText(name);

        if (counts != null && position < counts.size()) {
            int c = counts.get(position);
            if (c == 1) {
                holder.count.setText("1 song");
            } else {
                holder.count.setText(c + " songs");
            }
            holder.count.setVisibility(View.VISIBLE);
        } else {
            holder.count.setVisibility(View.GONE);
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView name;
        TextView count;
    }
}
