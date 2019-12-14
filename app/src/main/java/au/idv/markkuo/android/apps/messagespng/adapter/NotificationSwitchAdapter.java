package au.idv.markkuo.android.apps.messagespng.adapter;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import au.idv.markkuo.android.apps.messagespng.NotificationApp;
import au.idv.markkuo.android.apps.messagespng.AppNotificationListActivity;
import au.idv.markkuo.android.apps.messagespng.R;

public class NotificationSwitchAdapter extends ArrayAdapter<NotificationApp> {
    private ArrayList<Integer> checkedRows;
    private static final String TAG = NotificationSwitchAdapter.class.getSimpleName();
    private AppNotificationListActivity activity;

    // View lookup cache
    private static class ViewHolder {
        ImageView appIcon;
        TextView appName;
        Switch appSwitch;
    }

    public NotificationSwitchAdapter(AppNotificationListActivity activity) {
        super(activity, R.layout.app_notification_switch_row);
        this.checkedRows = new ArrayList<>();
        this.activity = activity;
    }

    public void setApps(List<NotificationApp> apps) {
        clear();
        addAll(apps);
        notifyDataSetChanged();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final NotificationApp app = getItem(position);
        ViewHolder viewHolder;

        if (convertView == null) {
            viewHolder = new ViewHolder();
            LayoutInflater inflater = LayoutInflater.from(getContext());
            convertView = inflater.inflate(R.layout.app_notification_switch_row, parent, false);
            viewHolder.appIcon = convertView.findViewById(R.id.appIcon);
            viewHolder.appName = convertView.findViewById(R.id.appName);
            viewHolder.appSwitch = convertView.findViewById(R.id.appSwitch);
            convertView.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) convertView.getTag();
        }

        viewHolder.appSwitch.setTag(position);
        viewHolder.appSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()
        {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked)
            {
                Log.d(TAG, "app " + app.getAppName() + " changed:" + isChecked);
                if (isChecked)
                    checkedRows.add((Integer)buttonView.getTag());
                else
                    checkedRows.remove(buttonView.getTag());
                app.setEnabled(isChecked);
                Log.v(TAG, "checked rows:" + checkedRows.toString());
                // apply the change
                activity.updateEnabledApp(app.getPackageName(), isChecked);
            }
        });

        viewHolder.appIcon.setImageDrawable(app.getAppIcon());
        viewHolder.appName.setText(app.getAppName());
        viewHolder.appSwitch.setChecked(app.getEnabled());

        return convertView;
    }
}
