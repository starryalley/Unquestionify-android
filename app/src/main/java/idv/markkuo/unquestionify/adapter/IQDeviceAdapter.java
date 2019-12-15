package idv.markkuo.unquestionify.adapter;

import java.util.List;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus;

public class IQDeviceAdapter extends ArrayAdapter<IQDevice> {
    private final String TAG = getClass().getSimpleName();
    private LayoutInflater mInflater;

    public IQDeviceAdapter(Context context) {
        super(context, android.R.layout.simple_list_item_2);

        mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mInflater.inflate(android.R.layout.simple_list_item_2, parent, false);
        }

        IQDevice device = getItem(position);
        String friendly = device.getFriendlyName();
        ((TextView)convertView.findViewById(android.R.id.text1)).setText((friendly == null) ? device.getDeviceIdentifier() + "" : device.getFriendlyName());
        ((TextView)convertView.findViewById(android.R.id.text2)).setText(device.getStatus().name());

        return convertView;
    }

    public void setDevices(List<IQDevice> devices) {
        clear();
        addAll(devices);
        notifyDataSetChanged();
    }

    public synchronized void updateDeviceStatus(IQDevice device, IQDeviceStatus status) {
        int numItems = this.getCount();
        Log.i(TAG, "device:" + device + " status changed:" + status.name());
        for(int i = 0; i < numItems; i++) {
            IQDevice local = getItem(i);
            if (local.getDeviceIdentifier() == device.getDeviceIdentifier()) {
                local.setStatus(status);
                notifyDataSetChanged();
                return;
            }
        }
    }
}
