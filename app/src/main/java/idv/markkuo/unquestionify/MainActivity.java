package idv.markkuo.unquestionify;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;

import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener;
import com.garmin.android.connectiq.ConnectIQ.IQDeviceEventListener;
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import idv.markkuo.unquestionify.adapter.IQDeviceAdapter;
import idv.markkuo.unquestionify.adapter.StatisticsAdapter;

public class MainActivity extends AppCompatActivity {
    private ConnectIQ mConnectIQ;
    private ListView deviceListView;
    private ListView statisticsListView;
    private IQDeviceAdapter deviceAdapter;
    private StatisticsAdapter statisticsAdapter;
    private boolean mSdkReady = false;
    private static final String TAG = MainActivity.class.getSimpleName();

    private boolean deviceStatusReceived = false;
    private boolean statisticsReceived = false;

    private MainActivityReceiver receiver;

    private IQDeviceEventListener mDeviceEventListener = new IQDeviceEventListener() {

        @Override
        public void onDeviceStatusChanged(IQDevice device, IQDeviceStatus status) {
            deviceStatusReceived = true;
            MainActivity.this.deviceAdapter.updateDeviceStatus(device, status);
            Log.i(TAG, "Device:" + device + " status changed:" + status);
        }

    };

    private ConnectIQListener mListener = new ConnectIQListener() {

        @Override
        public void onInitializeError(IQSdkErrorStatus errStatus) {
            mSdkReady = false;
        }

        @Override
        public void onSdkReady() {
            Log.d(TAG, "SDK ready");
            mSdkReady = true;
            loadDevices();
        }

        @Override
        public void onSdkShutDown() {
            mSdkReady = false;
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceListView = findViewById(R.id.deviceList);
        deviceAdapter = new IQDeviceAdapter(this);
        deviceListView.setAdapter(deviceAdapter);

        statisticsListView = findViewById(R.id.statisticsList);
        statisticsAdapter = new StatisticsAdapter(this);
        statisticsListView.setAdapter(statisticsAdapter);

        receiver = new MainActivityReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("idv.markkuo.unquestionify.NOTIFICATION_LISTENER_SERVICE_STATUS");
        registerReceiver(receiver, filter);

        // check notifications access permission
        String notificationListenerString = Settings.Secure.getString(this.getContentResolver(),"enabled_notification_listeners");
        if (notificationListenerString == null || !notificationListenerString.contains(getPackageName())) {
            // notification access has not been acquired yet
            openPermissionDialog();
            return;
        }

        // has access to the notifications

        // Initialize the SDK
        mConnectIQ = ConnectIQ.getInstance();
        mConnectIQ.initialize(this, true, mListener);

        // wait 3 sec for device status and statistics to be updated
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                // if those status are not yet received, request it again here
                // since the background Service may not have been started when this activity starts
                if (!deviceStatusReceived)
                    loadDevices();
                if (!statisticsReceived) {
                    Intent intent = new Intent();
                    intent.setAction("idv.markkuo.unquestionify.NOTIFICATION_LISTENER_SERVICE");
                    intent.putExtra("command", "startStatusReport");
                    sendBroadcast(intent);
                }
            }
        }, 3 * 1000);
    }

    private void showImage(Bitmap bitmap) {
        Dialog builder = new Dialog(this);
        builder.requestWindowFeature(Window.FEATURE_NO_TITLE);
        builder.getWindow().setBackgroundDrawable(
                new ColorDrawable(android.graphics.Color.TRANSPARENT));
        builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                //nothing;
            }
        });

        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(bitmap);
        builder.addContentView(imageView, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        builder.show();
    }

    private void openPermissionDialog() {
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();

        TextView title = new TextView(this);
        title.setText(R.string.needs_permission);
        title.setPadding(10, 10, 10, 10);   // Set Position
        title.setGravity(Gravity.CENTER);
        alertDialog.setCustomTitle(title);

        TextView msg = new TextView(this);
        msg.setText(R.string.needs_permission_detail);
        msg.setGravity(Gravity.CENTER_HORIZONTAL);
        alertDialog.setView(msg);

        // Set Button
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL,"Go to Settings", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        });

        alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE,"CANCEL", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                Toast.makeText(getApplicationContext(), "Unable to read any notifications", Toast.LENGTH_SHORT).show();
                ArrayList<Pair<String, String>> noperm = new ArrayList<>();
                noperm.add(new Pair<>("No Notification Permission", ""));
                statisticsAdapter.setStatistics(noperm);
            }
        });

        alertDialog.show();
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            List<IQDevice> devices = mConnectIQ.getKnownDevices();
            if (devices != null) {
                deviceAdapter.setDevices(devices);
                for (IQDevice device : devices) {
                    mConnectIQ.unregisterForDeviceEvents(device);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "unregistering failed:" + e);
        }

        Intent intent = new Intent();
        intent.setAction("idv.markkuo.unquestionify.NOTIFICATION_LISTENER_SERVICE");
        intent.putExtra("command", "stopStatusReport");
        sendBroadcast(intent);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mConnectIQ == null) {
            mConnectIQ = ConnectIQ.getInstance();
            mConnectIQ.initialize(this, true, mListener);
        }

        if (mSdkReady)
            loadDevices();

        Intent intent = new Intent();
        intent.setAction("idv.markkuo.unquestionify.NOTIFICATION_LISTENER_SERVICE");
        intent.putExtra("command", "startStatusReport");
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (mConnectIQ != null) {
                mConnectIQ.unregisterAllForEvents();
                mConnectIQ.shutdown(this);
            }
        } catch (InvalidStateException e) {
            // ignoring
        }
        unregisterReceiver(receiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.select_notify_apps) {
            Intent intent = new Intent(this, AppNotificationListActivity.class);
            startActivity(intent);
            return true;
        } else if (id == R.id.settings) {
            Intent intent = new Intent(this, NotificationSettingsActivity.class);
            startActivity(intent);
        }
        return super.onOptionsItemSelected(item);
    }

    public void loadDevices() {
        // Retrieve the list of known devices
        Log.d(TAG, "LoadDevices...");
        try {
            List<IQDevice> devices = mConnectIQ.getKnownDevices();
            if (devices != null) {
                deviceAdapter.setDevices(devices);
                for (IQDevice device : devices) {
                    mConnectIQ.registerForDeviceEvents(device, mDeviceEventListener);
                    Log.d(TAG, "device:" + device);
                }
            }
        } catch (InvalidStateException e) {
            Log.wtf(TAG, "register failed:" + e);
        } catch (ServiceUnavailableException e) {
            Log.e(TAG, "service unavailable:" + e);
        }
    }

    private class MainActivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            statisticsReceived = true;
            String statusString = intent.getStringExtra("service_status");
            try {
                JSONObject o = new JSONObject(statusString);
                Log.v(TAG, "service status:" + o);
                List<Pair<String, String>> statistics = new ArrayList<>();
                Iterator<String> keys = o.keys();

                while(keys.hasNext()) {
                    String key = keys.next();
                    String value = o.get(key).toString();
                    statistics.add(new Pair<>(key, value));
                }
                statisticsAdapter.setStatistics(statistics);
            } catch (Exception e) {
                Log.e(TAG, "error parsing json service_status:" + e);
            }
        }
    }
}
