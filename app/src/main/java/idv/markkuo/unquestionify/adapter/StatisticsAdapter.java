package idv.markkuo.unquestionify.adapter;

import android.content.Context;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

import idv.markkuo.unquestionify.R;

public class StatisticsAdapter extends ArrayAdapter<Pair<String, String>> {

    public StatisticsAdapter(Context context) {
        super(context, R.layout.statistic_row);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            convertView = inflater.inflate(R.layout.statistic_row, parent, false);
        }

        Pair<String, String> pair = getItem(position);
        ((TextView)convertView.findViewById(R.id.text1)).setText(pair.first);
        ((TextView)convertView.findViewById(R.id.text2)).setText(pair.second);

        return convertView;
    }

    public void setStatistics(List<Pair<String, String>> statistics) {
        clear();
        addAll(statistics);
        notifyDataSetChanged();
    }

}
