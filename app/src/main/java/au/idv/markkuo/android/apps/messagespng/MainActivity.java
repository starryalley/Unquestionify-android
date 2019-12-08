/**
 * Copyright (C) 2015 Garmin International Ltd.
 * Subject to Garmin SDK License Agreement and Wearables Application Developer Agreement.
 */
package au.idv.markkuo.android.apps.messagespng;

import java.util.List;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import au.idv.markkuo.android.apps.messagespng.adapter.IQDeviceAdapter;
import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener;
import com.garmin.android.connectiq.ConnectIQ.IQDeviceEventListener;
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

public class MainActivity extends ListActivity {

    private ConnectIQ mConnectIQ;
    private TextView mEmptyView;
    private IQDeviceAdapter mAdapter;
    private boolean mSdkReady = false;
    private static final String TAG = MainActivity.class.getSimpleName();
    private boolean mPermissionAcquired = false;

    private IQDeviceEventListener mDeviceEventListener = new IQDeviceEventListener() {

        @Override
        public void onDeviceStatusChanged(IQDevice device, IQDeviceStatus status) {
            mAdapter.updateDeviceStatus(device, status);
        }

    };

    private ConnectIQListener mListener = new ConnectIQListener() {

        @Override
        public void onInitializeError(IQSdkErrorStatus errStatus) {
            if( null != mEmptyView )
                mEmptyView.setText(R.string.initialization_error + errStatus.name());
            mSdkReady = false;
        }

        @Override
        public void onSdkReady() {
            loadDevices();
            mSdkReady = true;
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

        mAdapter = new IQDeviceAdapter(this);
        getListView().setAdapter(mAdapter);

        // Here we are specifying that we want to use a WIRELESS bluetooth connection.
        // We could have just called getInstance() which would by default create a version
        // for WIRELESS, unless we had previously gotten an instance passing TETHERED
        // as the connection type.
        //mConnectIQ = ConnectIQ.getInstance(this, IQConnectType.WIRELESS);// use TETHERED for CIQ simulator

        // Initialize the SDK
        //mConnectIQ.initialize(this, true, mListener);

        mEmptyView = (TextView)findViewById(android.R.id.empty);

        // check notifications access permission
        String notificationListenerString = Settings.Secure.getString(this.getContentResolver(),"enabled_notification_listeners");
        if (notificationListenerString == null || !notificationListenerString.contains(getPackageName()))
        {
            // notification access has not been acquired yet
            openPermissionDialog();
        }else{
            // has access to the notifications
            mPermissionAcquired = true;
        }

    }

    private void openPermissionDialog() {

        AlertDialog alertDialog = new AlertDialog.Builder(this).create();

        // Set Custom Title
        TextView title = new TextView(this);
        // Title Properties
        title.setText(R.string.needs_permission);
        title.setPadding(10, 10, 10, 10);   // Set Position
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.BLACK);
        title.setTextSize(20);
        alertDialog.setCustomTitle(title);

        // Set Message
        TextView msg = new TextView(this);
        // Message Properties
        msg.setText(R.string.needs_permission_detail);
        msg.setGravity(Gravity.CENTER_HORIZONTAL);
        msg.setTextColor(Color.BLACK);
        alertDialog.setView(msg);

        // Set Button
        // you can more buttons
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL,"Go to Settings", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        });

        alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE,"CANCEL", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                Toast.makeText(getApplicationContext(), "Unable to read notifications", Toast.LENGTH_SHORT).show();
            }
        });

        //new Dialog(getApplicationContext());
        alertDialog.show();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mSdkReady)
            loadDevices();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
//
//        // It is a good idea to unregister everything and shut things down to
//        // release resources and prevent unwanted callbacks.
//        try {
//            mConnectIQ.unregisterAllForEvents();
//            mConnectIQ.shutdown(this);
//        } catch (InvalidStateException e) {
//            // This is usually because the SDK was already shut down
//            // so no worries.
//        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.load_devices) {
            loadDevices();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        IQDevice device = mAdapter.getItem(position);

        Intent intent = new Intent(this, DeviceActivity.class);
        intent.putExtra(DeviceActivity.IQDEVICE, device);
        startActivity(intent);
    }

    public void loadDevices() {
        // Retrieve the list of known devices
        try {
            List<IQDevice> devices = mConnectIQ.getKnownDevices();

            if (devices != null) {
                mAdapter.setDevices(devices);

                // Let's register for device status updates.  By doing so we will
                // automatically get a status update for each device so we do not
                // need to call getStatus()
                for (IQDevice device : devices) {
                    mConnectIQ.registerForDeviceEvents(device, mDeviceEventListener);
                }
            }

        } catch (InvalidStateException e) {
            // This generally means you forgot to call initialize(), but since
            // we are in the callback for initialize(), this should never happen
            Log.wtf(TAG, "register failed:" + e);
        } catch (ServiceUnavailableException e) {
            // This will happen if for some reason your app was not able to connect
            // to the ConnectIQ service running within Garmin Connect Mobile.  This
            // could be because Garmin Connect Mobile is not installed or needs to
            // be upgraded.
            if(mEmptyView != null)
                mEmptyView.setText(R.string.service_unavailable);
        }
    }
}
