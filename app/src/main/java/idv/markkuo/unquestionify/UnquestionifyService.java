package idv.markkuo.unquestionify;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import ar.com.hjg.pngj.chunks.ChunkCopyBehaviour;
import ar.com.hjg.pngj.chunks.PngChunkPLTE;

public class UnquestionifyService extends NotificationListenerService {
    private final String TAG = this.getClass().getSimpleName();
    private static final String PREF_UNIQUE_ID = "UnquestionifyAppPref";

    // for starting app on the watch
    private static final String CIQ_APP = "c2842d1b-ad5c-47c6-b28f-cc495abd7d32";
    private ConnectIQ mConnectIQ;
    private boolean mCIQReady = false;

    private IQDevice mCIQDevice;
    private IQApp mCIQApp;

    private boolean mOpenAppRequestInProgress = false;

    private UnquestionifyServiceReceiver serviceReceiver;
    private boolean connected = false;
    private NotificationHTTPD server;
    private final ArrayList<WatchNotification> mNotifications = new ArrayList<>();
    private Set<String> mAllowedSources;
    private long lastUpdatedTS = 0; //last updated time stamp for notification
    private final Map<String, Long> lastNotificationWhen = new HashMap<>();

    private String sessionId;
    private String relaySessionId = null; // will not reset, and will be generated once when this app runs for the first time
    private boolean relayStarted = false;
    private final String relayServer = "https://fill_in_the_relay_server_domain_here";
    private Handler sessionExpireHandler;

    // for message types
    // Message tags
    private static final int MSG_STARTUP = 1;
    private static final int MSG_POSTED = 2;
    private static final int MSG_REMOVED = 3;
    private static final int MSG_ORDER = 4;
    private static final int MSG_DISMISS = 5;


    // for statistics
    private static int sessionCount;
    private static int promptShownCount;
    private static int promptNotShownCount;
    private static int notificationQueryCount;
    private static int bitmapQueryCount;
    private static int notificationDetailQueryCount;
    private static int dismissQueryCount;
    private static int forbiddenCount;
    private static int totalNotificationCount;
    private static long totalBitmapBytes;

    private Handler statusReportHandler;

    // for setting
    private static final int defaultTextSize = 22; //24 is better for F6pro, 20 is fine on vivoactive 4s

    // how many pixels to enlarge the square inside the circle
    // 40 is better but a long text on F6Pro can result in a 2500-byte png which is too big
    private static final int defaultSquareWidthOffset = 20;

    // if there is any pending request to start watchapp, this will be true
    private boolean pendingStartApp = false;

    private RequestQueue relayRequestQueue;
    private final ConnectIQ.ConnectIQListener mCIQListener = new ConnectIQ.ConnectIQListener() {

        @Override
        public void onInitializeError(ConnectIQ.IQSdkErrorStatus errStatus) {
            Log.e(TAG, "initializing CIQ SDK error:" + errStatus.name());
            mCIQReady = false;
        }

        @Override
        public void onSdkReady() {
            Log.i(TAG, "initializing CIQ SDK done");
            mOpenAppRequestInProgress = false;
            loadCIQDevices();
            mCIQReady = true;
            if (pendingStartApp) {
                startWatchApp();
                pendingStartApp = false;
            }
        }

        @Override
        public void onSdkShutDown() {
            mCIQReady = false;
        }

    };

    private final ConnectIQ.IQOpenApplicationListener mCIQOpenAppListener = new ConnectIQ.IQOpenApplicationListener() {
        @Override
        public void onOpenApplicationResponse(IQDevice device, IQApp app, ConnectIQ.IQOpenApplicationStatus status) {
            Log.i(TAG, "CIQ App status:" + status.name());
            if (status == ConnectIQ.IQOpenApplicationStatus.PROMPT_SHOWN_ON_DEVICE)
                promptShownCount++;
            else if (status == ConnectIQ.IQOpenApplicationStatus.PROMPT_NOT_SHOWN_ON_DEVICE)
                promptNotShownCount++;

            // clear the flag
            mOpenAppRequestInProgress = false;
        }
    };

    private final Comparator<WatchNotification> mNotificationComparator = new Comparator<WatchNotification>() {
        @Override
        public int compare(WatchNotification a, WatchNotification b) {
            return Long.compare(a.when, b.when);
        }
    };

    private synchronized String getRelaySessionId() {
        if (relaySessionId == null) {
            SharedPreferences sharedPrefs = getApplicationContext().getSharedPreferences(
                    PREF_UNIQUE_ID, Context.MODE_PRIVATE);
            relaySessionId = sharedPrefs.getString(PREF_UNIQUE_ID, null);
            if (relaySessionId == null) {
                relaySessionId = UUID.randomUUID().toString();
                SharedPreferences.Editor editor = sharedPrefs.edit();
                editor.putString(PREF_UNIQUE_ID, relaySessionId);
                editor.apply();
            }
        }
        return relaySessionId;
    }

    private String getAppName(String packageName) {
        final PackageManager pm = getApplicationContext().getPackageManager();
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(packageName, 0);
        } catch (final PackageManager.NameNotFoundException e) {
            ai = null;
        }
        return (String) (ai != null ? pm.getApplicationLabel(ai) : "(unknown)");
    }

    private static boolean isPureAscii(String v) {
        return StandardCharsets.US_ASCII.newEncoder().canEncode(v);
        // or "ISO-8859-1" for ISO Latin 1
    }

    private ByteArrayInputStream bitmapToInputStream(Bitmap bitmap) {
        return bitmapToInputStream(bitmap, true, 1);
    }

    private ByteArrayInputStream bitmapToInputStream(Bitmap bitmap, boolean grayscale, int bitdepth) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        ByteArrayInputStream bais = new ByteArrayInputStream(stream.toByteArray());
        PngReader reader = new PngReader(bais);
        Log.v(TAG, "PNG:" + reader.toString());


        // create PNG writer
        ByteArrayOutputStream baos = new ByteArrayOutputStream(stream.toByteArray().length);
        ImageInfo imiw = new ImageInfo(reader.imgInfo.cols, reader.imgInfo.rows, bitdepth, false, grayscale, !grayscale);
        PngWriter writer = new PngWriter(baos, imiw);
        writer.setCompLevel(9); //max compression
        //writer.setFilterType(FilterType.FILTER_ADAPTIVE_FULL);// doesn't help

        if (!grayscale) {
            // set palette
            PngChunkPLTE palette = writer.getMetadata().createPLTEChunk();
            palette.setNentries(2);
            palette.setEntry(0, 0, 0, 0);
            palette.setEntry(0, 255, 0, 0);
            palette.setEntry(0, 0, 255, 0);
            palette.setEntry(0, 0, 0, 255);
            palette.setEntry(1, 255, 255, 255);
            //PngChunkSingle palette = builder.buildPngChunkPaletteFromCurrentMap(imiw);
            writer.getMetadata().queueChunk(palette);
        }

        writer.copyChunksFrom(reader.getChunksList(), ChunkCopyBehaviour.COPY_ALL_SAFE);

        ImageLineInt linew = new ImageLineInt(imiw);
        int channels = reader.imgInfo.channels;
        for (int row = 0; row < reader.imgInfo.rows; row++) {
            ImageLineInt l1 = (ImageLineInt) reader.readRow(row);
            int[] scanline = l1.getScanline();

            for (int j = 0, k = 0; j < reader.imgInfo.cols; j++, k += channels) {
                if (scanline[k] > 128) // if R value is > 128
                    linew.getScanline()[j] = 1;
                else
                    linew.getScanline()[j] = 0;
            }
            writer.writeRow(linew, row);

        }
        // cleanup
        reader.end();
        writer.end();
        totalBitmapBytes += baos.toByteArray().length;
        Log.d(TAG, "Compressed PNG (" + reader.imgInfo.cols + "x" + reader.imgInfo.rows + "): " + bitmap.getByteCount() + " => " + baos.toByteArray().length + " bytes");
        //bitmap.recycle();
        return new ByteArrayInputStream(baos.toByteArray());
    }

    private void loadAllowedApps() {
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(
                PREF_UNIQUE_ID, Context.MODE_PRIVATE);
        mAllowedSources = preferences.getStringSet("allowed_apps", null);
        if (mAllowedSources == null) {
            mAllowedSources = new HashSet<>();
            mAllowedSources.add("jp.naver.line.android");//Line
            mAllowedSources.add("com.whatsapp"); //what's app
            mAllowedSources.add("com.facebook.orca"); //fb messenger
            mAllowedSources.add("com.tencent.mm");//WeChat
            mAllowedSources.add("com.google.android.apps.messaging"); //google Messages
            Log.d(TAG, "No saved data. Saving allowed_apps now");
            saveAllowedSources();
        }
        Log.d(TAG, "allowed app:" + mAllowedSources.toString());
    }

    static void loadStatistics(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREF_UNIQUE_ID, Context.MODE_PRIVATE);
        sessionCount = preferences.getInt("sessionCount", 0);
        promptShownCount = preferences.getInt("promptShownCount", 0);
        promptNotShownCount = preferences.getInt("promptNotShownCount", 0);
        notificationQueryCount = preferences.getInt("notificationQueryCount", 0);
        bitmapQueryCount = preferences.getInt("bitmapQueryCount", 0);
        notificationDetailQueryCount = preferences.getInt("notificationDetailQueryCount", 0);
        dismissQueryCount = preferences.getInt("dismissQueryCount", 0);
        forbiddenCount = preferences.getInt("forbiddenCount", 0);
        totalNotificationCount = preferences.getInt("totalNotificationCount", 0);
        totalBitmapBytes = preferences.getLong("totalBitmapBytes", 0);
    }

    static void saveStatistics(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREF_UNIQUE_ID, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("sessionCount", sessionCount);
        editor.putInt("promptShownCount", promptShownCount);
        editor.putInt("promptNotShownCount", promptNotShownCount);
        editor.putInt("notificationQueryCount", notificationQueryCount);
        editor.putInt("bitmapQueryCount", bitmapQueryCount);
        editor.putInt("notificationDetailQueryCount", notificationDetailQueryCount);
        editor.putInt("dismissQueryCount", dismissQueryCount);
        editor.putInt("forbiddenCount", forbiddenCount);
        editor.putInt("totalNotificationCount", totalNotificationCount);
        editor.putLong("totalBitmapBytes", totalBitmapBytes);
        editor.apply();
    }

    static void resetStatistics(Context context) {
        sessionCount = promptShownCount = promptNotShownCount = notificationQueryCount =
                bitmapQueryCount = notificationDetailQueryCount = dismissQueryCount =
                        forbiddenCount = totalNotificationCount = 0;
        totalBitmapBytes = 0;
        saveStatistics(context);
    }

    private boolean getNonASCIIOnly() {
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(PREF_UNIQUE_ID, Context.MODE_PRIVATE);
        return preferences.getBoolean("nonascii", false);
    }

    private boolean getGroupSimilarMessage() {
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(PREF_UNIQUE_ID, Context.MODE_PRIVATE);
        return preferences.getBoolean("group_msg", false);
    }

    private boolean getShowPrompt() {
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(PREF_UNIQUE_ID, Context.MODE_PRIVATE);
        return preferences.getBoolean("show_prompt", true);
    }

    private void saveAllowedSources() {
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(PREF_UNIQUE_ID, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putStringSet("allowed_apps", mAllowedSources);
        editor.apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceReceiver = new UnquestionifyServiceReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("idv.markkuo.unquestionify.NOTIFICATION_LISTENER_SERVICE");
        registerReceiver(serviceReceiver, filter);

        loadAllowedApps();
        loadStatistics(getApplicationContext());

        // save default textsize setting
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(PREF_UNIQUE_ID, Context.MODE_PRIVATE);
        if (!preferences.contains("textsize")) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("textsize", Integer.toString(defaultTextSize));
            editor.apply();
        }

        // initialise CIQ
        mConnectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS);
        // Initialize the SDK
        // This will cause the app to crash in ConnectIQ library because the context passed to CIQ is a Service,
        // and CIQ seems to want to display a dialog to ask user to install Garmin Connect app. This will cause:
        // E/AndroidRuntime: FATAL EXCEPTION: main
        //    Process: idv.markkuo.unquestionify, PID: 8508
        //    android.view.WindowManager$BadTokenException: Unable to add window -- token null is not for an application
        //        at android.view.ViewRootImpl.setView(ViewRootImpl.java:682)
        //        at android.view.WindowManagerGlobal.addView(WindowManagerGlobal.java:342)
        //        at android.view.WindowManagerImpl.addView(WindowManagerImpl.java:93)
        //        at android.app.Dialog.show(Dialog.java:316)
        //        at com.garmin.android.connectiq.ConnectIQ$1.run(ConnectIQ.java:557)
        //        at android.os.Handler.handleCallback(Handler.java:751)
        //        at android.os.Handler.dispatchMessage(Handler.java:95)
        //        at android.os.Looper.loop(Looper.java:154)
        //        at android.app.ActivityThread.main(ActivityThread.java:6077)
        //        at java.lang.reflect.Method.invoke(Native Method)
        //        at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:866)
        //        at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:756)
        // Let's ignore it for now and assume user already has Garmin Connect app installed.
        mConnectIQ.initialize(this, true, mCIQListener);

        mCIQApp = new IQApp(CIQ_APP);

        // start server
        try {
            server = new NotificationHTTPD();
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "error starting httpd:" + e);
        }

        relayRequestQueue = Volley.newRequestQueue(this);
        relayStartSession();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        saveStatistics(getApplicationContext());
        server.stop();
        try {
            mConnectIQ.shutdown(this);
        } catch (Exception e) {
            Log.w(TAG, "Cannot shutdown CIQ:" + e);
        }
        server.stop();
        unregisterReceiver(serviceReceiver);

        if (relayRequestQueue != null) {
            relayRequestQueue.cancelAll(TAG);
        }
        relayEndSession();
    }

    public void loadCIQDevices() {
        try {
            List<IQDevice> devices = mConnectIQ.getKnownDevices();
            if (devices != null) {
                Log.i(TAG, "CIQ devices count:" + devices.size());
                if (devices.size() > 0) {
                    mCIQDevice = devices.get(0);
                    Log.i(TAG, "CIQ Device:" + mCIQDevice.getFriendlyName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "register failed:" + e);
        }
    }

    private void updateSummaryBitmap() {
        if (mNotifications.size() > 0) {
            WatchNotification wn = mNotifications.get(0);
            // glance summary bitmap
            relayUpdate("summary", 0, wn.getSummaryBitmap());
        }
    }

    private void uploadLatestToRelay() {
        relayStartSession();
        WatchNotification wn = mNotifications.get(0);
        updateSummaryBitmap();
        // overview bitmap
        relayUpdate(wn.id, -1, wn.getOverviewBitmap());
        // detail bitmaps
        for (int i = 0; i < wn.getDetailBitmapCount(); i++) {
            relayUpdate(wn.id, i, wn.getDetailBitmap(i));
        }
    }

    private void addNotification(StatusBarNotification sbn) {
        // ignore group summary messages
        if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0/* ||
                (sbn.getNotification().flags & Notification.FLAG_LOCAL_ONLY) != 0*/) {
            Log.v(TAG, "[ignore group summary] [" + sbn.getPackageName() + "]:" + sbn.getNotification().tickerText + " (flag:" + sbn.getNotification().flags + ")");
            return;
        }
        // ignore old notification
        Long lastWhen = lastNotificationWhen.get(sbn.getKey());
        if(lastWhen != null && lastWhen >= sbn.getNotification().when)
            return;

        // filter message content based on if it contains non-ascii chars
        boolean nonASCIIOnly = getNonASCIIOnly();
        String notificationText = getNotificationText(sbn);
        if (nonASCIIOnly && isPureAscii(notificationText)) {
            Log.v(TAG, "[ignore ascii only] [" + sbn.getPackageName() + "]:" + sbn.getNotification().tickerText + " (flag:" + sbn.getNotification().flags + ")");
            return;
        }

        lastNotificationWhen.put(sbn.getKey(), sbn.getNotification().when);
        totalNotificationCount++;

        //use appending to existing notification if we want to group similar message
        if (getGroupSimilarMessage()) {
            for (int i = 0; i < mNotifications.size(); i++) {
                WatchNotification n = mNotifications.get(i);
                // similar message is defined as having the same StatusBarNotification key //and same notification title
                //if (n.key.equals(sbn.getKey()) && n.title.equals(getNotificationTitle(sbn))) {
                if (n.key.equals(sbn.getKey())) {
                    // add to existing
                    n.appendMessage(notificationText, sbn.getNotification().when);
                    // move this to queue start
                    mNotifications.add(0, mNotifications.remove(i));
                    uploadLatestToRelay();
                    Log.d(TAG, "[append]" + n.toLogString());
                    lastUpdatedTS = System.currentTimeMillis();
                    startWatchApp();
                    return;
                }
            }
        } else {
            // when not grouping, let's remove previous notification with same key
            Log.d(TAG, "try to remove old notification");
            removeNotification(sbn);
        }

        // doesn't exists, let's add to the beginning of the list
        mNotifications.add(0, new WatchNotification(getApplicationContext(), sbn.getKey(), getNotificationTitle(sbn),
                notificationText, getAppName(sbn.getPackageName()),
                sbn.getNotification().getSmallIcon(), sbn.getNotification().when));
        Log.d(TAG, "[add]" + mNotifications.get(0).toLogString());
        uploadLatestToRelay();


        /*
        // sort mNotifications by when descendingly
        mNotifications.sort(Collections.reverseOrder(new Comparator<WatchNotification>() {
            @Override
            public int compare(WatchNotification a, WatchNotification b) {
                return Long.compare(a.when, b.when);
            }
        }));
        */
        lastUpdatedTS = System.currentTimeMillis();
        startWatchApp();
    }

    private void removeNotification(StatusBarNotification sbn) {
        for (int i = 0; i < mNotifications.size(); i++) {
            WatchNotification n = mNotifications.get(i);
            //if (n.key.equals(sbn.getKey()) && n.title.equals(getNotificationTitle(sbn))) {
            if (n.key.equals(sbn.getKey())) {
                relayRemove(n.id);
                mNotifications.remove(i);
                lastNotificationWhen.remove(n.key);
                Log.d(TAG, "[remove]" + n.toLogString());
                lastUpdatedTS = System.currentTimeMillis();
                break;
            }
        }
        updateSummaryBitmap();
    }

    private static String getNotificationTitle(StatusBarNotification sbn) {
        Bundle b = sbn.getNotification().extras;
        Object data = b.get("android.title");
        return data == null ? "" : data.toString();
    }

    private static String getNotificationText(StatusBarNotification sbn) {
        Bundle b = sbn.getNotification().extras;
        Object data = b.get("android.bigText");
        if (data == null)
            data = b.get("android.text");
        return data == null ? "" : data.toString();
    }

    @Override
    public void onListenerConnected () {
        connected = true;
        Log.v(TAG, "Listener connected");
        Message.obtain(mNotificationHandler, MSG_STARTUP).sendToTarget();
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        Message.obtain(mNotificationHandler, MSG_ORDER).sendToTarget();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (!mAllowedSources.contains(sbn.getPackageName())) {
            return;
        }

        Message.obtain(mNotificationHandler, MSG_POSTED, sbn).sendToTarget();
        Log.v(TAG, "onNotificationPosted " + sbn.getNotification().tickerText + "\t" + getNotificationText(sbn) + "(" + sbn.getPackageName() + ")");
        //addNotification(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (!mAllowedSources.contains(sbn.getPackageName())) {
            return;
        }

        Message.obtain(mNotificationHandler, MSG_REMOVED, sbn).sendToTarget();
        Log.v(TAG, "onNotificationRemoved " + sbn.getNotification().tickerText + "\t" + getNotificationText(sbn) + "(" + sbn.getPackageName() + ")");
        //removeNotification(sbn);
    }

    private void startWatchApp() {
        if (!getShowPrompt())
            return;
        if (mOpenAppRequestInProgress)
            return;
        if (!mCIQReady) {
            pendingStartApp = true;
            mConnectIQ.initialize(this, true, mCIQListener);
            mOpenAppRequestInProgress = true;
            Log.w(TAG, "CIQ not ready, re-initialise CIQ now");
            return;
        }
        //if (mCIQApp.getStatus() != IQApp.IQAppStatus.INSTALLED)
        //    return;
        mOpenAppRequestInProgress = true;
        Log.i(TAG, "starting watch app");
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // clear the flag after 10 seconds
                if (mOpenAppRequestInProgress) {
                    Log.w(TAG, "watch app doesn't seem to be responsive. Clearing state");
                    mOpenAppRequestInProgress = false;
                }
            }
        }, 1000 * 10);

        // Send a message to open the app
        try {
            if (mCIQDevice != null)
                mConnectIQ.openApplication(mCIQDevice, mCIQApp, mCIQOpenAppListener);
            else
                Log.w(TAG, "CIQ Device not present");
        } catch (InvalidStateException e) {
            // SDK becomes uninitialised. Redo it
            pendingStartApp = true;
            mConnectIQ.initialize(this, true, mCIQListener);
        } catch (Exception e) {
            Log.e(TAG, "openApplication failed:" + e);
        }
    }

    @SuppressLint("DefaultLocale")
    private void broadcastServiceStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("Service Running", connected ? "Running" : "Stopped");
            o.put("Watchapp Prompt Shown", promptShownCount);
            o.put("Watchapp Prompt Not Shown", promptNotShownCount);
            o.put("Watchapp Use Count", sessionCount);
            o.put("Notification Query Count", notificationQueryCount);
            o.put("Detail Notification Query Count", notificationDetailQueryCount);
            o.put("Total Bitmap Requested", bitmapQueryCount);
            String unit = "B";
            float val = totalBitmapBytes;
            if (totalBitmapBytes > 1024) {// convert to KB
                val /= 1024;
                unit = "KB";
            }
            if (val > 1024) {// convert to MB
                val /= 1024;
                unit = "MB";
            }
            o.put("Total Transferred Bitmap Size", String.format("%.1f", val) + " " + unit);
            o.put("Notification Dismissal Count", dismissQueryCount);
            o.put("Forbidden Requests", forbiddenCount);
            o.put("Current Notification Count", mNotifications.size());
            o.put("Total Notification Count", totalNotificationCount);
        } catch (Exception e) {
            Log.e(TAG, "Unable to create json object:" + e);
        }
        Log.v(TAG, "Broadcasting service status");
        // send broadcast
        Intent i = new Intent("idv.markkuo.unquestionify.NOTIFICATION_LISTENER_SERVICE_STATUS");
        i.putExtra("service_status", o.toString());
        sendBroadcast(i);
        // save current statistics
        saveStatistics(getApplicationContext());
    }

    private class UnquestionifyServiceReceiver extends BroadcastReceiver{

        @Override
        public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra("command");
            if (command == null)
                return;
            String packageName;
            switch (command) {
            case "addApp":
                //UnquestionifyService.this.cancelAllNotifications();
                packageName = intent.getStringExtra("app");
                if (packageName != null && !packageName.equals("")) {
                    mAllowedSources.add(packageName);
                    Log.d(TAG, "adding " + packageName + " to allowed sources");
                    saveAllowedSources();
                }
                break;
            case "removeApp":
                packageName = intent.getStringExtra("app");
                if (packageName != null && !packageName.equals("")) {
                    mAllowedSources.remove(packageName);
                    Log.d(TAG, "removing " + packageName + " from allowed sources");
                    saveAllowedSources();
                }
                break;
            case "startStatusReport":
                if (statusReportHandler == null) {
                    Log.d(TAG, "starting status report");
                    broadcastServiceStatus();

                    // start a timer to send it every 10 sec
                    statusReportHandler = new Handler();
                    final int delay = 10 * 1000; //milliseconds
                    statusReportHandler.postDelayed(new Runnable() {
                        @Override
                        public void run(){
                            broadcastServiceStatus();
                            statusReportHandler.postDelayed(this, delay);
                        }
                    }, delay);
                }
                break;
            case "stopStatusReport":
                if (statusReportHandler != null) {
                    Log.d(TAG, "stopping status report");
                    statusReportHandler.removeCallbacksAndMessages(null);
                    statusReportHandler = null;
                }
                break;
            }
        }
    }

    private void scheduleSessionExpire() {
        if (sessionExpireHandler != null) {
            sessionExpireHandler.removeCallbacksAndMessages(null);
        } else
            sessionExpireHandler = new Handler(Looper.getMainLooper());
        sessionExpireHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // clear the session after 30 seconds
                Log.i(TAG, "session expired");
                sessionId = "";
            }
        }, 1000 * 60 * 3); // 3 min
    }

    private void fetchActive() {
        // get current notification
        for (StatusBarNotification sbn : UnquestionifyService.this.getActiveNotifications()) {
            if (mAllowedSources.contains(sbn.getPackageName()))
                addNotification(sbn);
        }
        Collections.sort(mNotifications, Collections.reverseOrder(mNotificationComparator));
        // debugging only below
        for (WatchNotification n: mNotifications) {
            Log.d(TAG, "[active notification] " + n.toLogString());
        }
    }

    private void relayStartSession() {
        if (relayStarted)
            return;
        relayStarted = true;
        String url = relayServer + "/session?session=" + getRelaySessionId();
        StringRequest request = new StringRequest(Request.Method.POST, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        // Display the first 500 characters of the response string.
                        Log.d(TAG, "relayStartSession done");
                    }
                }, new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "relayStartSession:" + error);
                    }
                }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String>  params = new HashMap<String, String>();
                params.put("app-id", CIQ_APP);
                return params;
            }
        };
        request.setTag(TAG);
        relayRequestQueue.add(request);
    }

    private void relayEndSession() {
        relayStarted = false;
        String url = relayServer + "/session?session=" + getRelaySessionId();
        StringRequest request = new StringRequest(Request.Method.DELETE, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        // Display the first 500 characters of the response string.
                        Log.d(TAG, "relayEndSession done");
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e(TAG, "relayEndSession:" + error);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String>  params = new HashMap<String, String>();
                params.put("app-id", CIQ_APP);
                return params;
            }
        };
        request.setTag(TAG);
        relayRequestQueue.add(request);
    }


    private void relayUpdate(final String notificationId, int page, Bitmap bitmap) {
        String url = relayServer + "/notifications/" + notificationId + "/" + page + "?session=" + getRelaySessionId();
        StringRequest request = new StringRequest(Request.Method.PUT, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.d(TAG, "relayUpdate done");
                    }
                }, new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "relayUpdate:" + error);
                    }
                }) {
            @Override
            public byte[] getBody() throws AuthFailureError {
                try {
                    ByteArrayInputStream inputStream = bitmapToInputStream(bitmap);
                    byte[] targetArray = new byte[inputStream.available()];
                    inputStream.read(targetArray);
                    return targetArray;
                } catch (IOException e) {
                    Log.e(TAG, "relayUpdate error:" + e);
                    return null;
                }
            }
        };
        request.setTag(TAG);
        relayRequestQueue.add(request);
    }

    private void relayRemove(final String notificationId) {
        String url = relayServer + "/notifications/" + notificationId + "/0?session=" + getRelaySessionId();
        StringRequest request = new StringRequest(Request.Method.DELETE, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.d(TAG, "relayRemove done");
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "relayRemove:" + error);
                    }
                });
        request.setTag(TAG);
        relayRequestQueue.add(request);
    }

    private final Handler mNotificationHandler = new NotificationHandler();

    private class NotificationHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            StatusBarNotification sbn = null;
            if (msg.obj instanceof StatusBarNotification) {
                sbn = (StatusBarNotification) msg.obj;
            }

            switch (msg.what) {
                case MSG_STARTUP:
                    fetchActive();
                    break;
                case MSG_POSTED:
                    synchronized (mNotifications) {
                        assert sbn != null;
                        addNotification(sbn);
                        Collections.sort(mNotifications, Collections.reverseOrder(mNotificationComparator));
                    }
                    break;
                case MSG_REMOVED:
                    synchronized (mNotifications) {
                        removeNotification(sbn);
                        //Collections.sort(mNotifications, Collections.reverseOrder(mNotificationComparator));
                    }
                    break;
                case MSG_ORDER:
                    synchronized (mNotifications) {
                        Collections.sort(mNotifications, Collections.reverseOrder(mNotificationComparator));
                    }
                    break;
                case MSG_DISMISS:
                    /*
                    if (msg.obj instanceof WatchNotification) {
                        final WatchNotification n = (WatchNotification) msg.obj;
                        mRankingMap.getRanking(n.key, mTmpRanking);
                        n.
                        StatusBarNotification sbn = sNotifications.get(mTmpRanking.getRank());
                        if ((sbn.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0 &&
                                sbn.getNotification().contentIntent != null) {
                            try {
                                sbn.getNotification().contentIntent.send();
                            } catch (PendingIntent.CanceledException e) {
                                Log.d(TAG, "failed to send intent for " + n.key, e);
                            }
                        }
                        cancelNotification(n.key);
                    }*/
                    break;
            }
        }
    }

    // the HTTPD service for watch to read/dismiss notifications
    private class NotificationHTTPD extends NanoHTTPD {
        private final String TAG = this.getClass().getSimpleName();

        NotificationHTTPD() {
            super(8080);
        }

        private JSONObject createErrorJSONResponse(String errText) {
            JSONObject json = new JSONObject();
            try {
                json.put("error", errText);
            } catch (JSONException e) {
                Log.e(TAG, "unable to create json object");
            }
            return json;
        }

        @Override
        public Response serve(IHTTPSession session) {
            Method method = session.getMethod();
            String uri = session.getUri();

            String remoteAddress = session.getHeaders().get("remote-addr");
            if (remoteAddress != null && !remoteAddress.equals("127.0.0.1")) {
                Log.e(TAG, "forbidding connection other than localhost. Incoming address:" + remoteAddress);
                forbiddenCount++;
                return new NanoHTTPD.Response(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "SERVICE FORBIDDEN");
            }

            Map<String, String> params = session.getParms();
            //Map<String, String> headers = session.getHeaders();
            //Log.v(TAG, "=== [" + method.name() + "] URI:" + uri + ", Headers:" + headers + ", Params:" + params);

            // ========== request session ============
            if (method == Method.GET && uri.equals("/request_session")) {
                // check appid param
                if (params.get("appid") == null || !Objects.equals(params.get("appid"), CIQ_APP)) {
                    Log.e(TAG, "/request_session requested with incorrect appid");
                    return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                            createErrorJSONResponse("Wrong appid").toString());
                }

                boolean isRound = true; //assume default watch is round shape
                //int page = -1; // -1 is the overview page (max 3 lines)
                int watchWidth = 260, watchHeight = 260;
                if (params.get("width") != null)
                    watchWidth = Integer.parseInt(Objects.requireNonNull(params.get("width")));
                if (params.get("height") != null)
                    watchHeight = Integer.parseInt(Objects.requireNonNull(params.get("height")));
                if (params.get("shape") != null)
                    isRound = Objects.equals(params.get("shape"), "round");
                Log.d(TAG, "Watch Dimension:" + watchWidth + "x" + watchHeight + " pixels (round:" + isRound + ")");
                // calculate proper width/height for round watches
                if (isRound) {
                    // round screen, calculate max square inside the circle of diameter width
                    watchWidth = watchHeight = (int) Math.ceil(Math.sqrt(watchWidth * watchWidth / 2.0)) + defaultSquareWidthOffset;
                }
                Log.d(TAG, "Png will be of " + watchWidth + "x" + watchHeight + " pixels");
                WatchNotification.setDimension(watchWidth, watchHeight);

                // update sessionId
                sessionId = UUID.randomUUID().toString();
                Log.i(TAG, "Session started");
                scheduleSessionExpire();
                sessionCount++;
                // create JSON
                JSONObject json = new JSONObject();
                try {
                    json.put("session", sessionId);
                    json.put("relay_session", getRelaySessionId());
                } catch (JSONException e) {
                    Log.e(TAG, "unable to create json object");
                    return new NanoHTTPD.Response(Response.Status.INTERNAL_ERROR, "application/json",
                            createErrorJSONResponse("unable to create response json").toString());
                }
                return new NanoHTTPD.Response(Response.Status.OK, "application/json", json.toString());
            }

//            //TODO: testing only, move to the end
//            if (method == Method.GET && uri.startsWith("/icons/")) {
//                if (mNotifications.size() > 0) {
//                    Bitmap bitmap = mNotifications.get(0).getIcon();
//                    if (bitmap != null)
//                        return new NanoHTTPD.Response(Response.Status.OK, "image/png", bitmapToInputStreamUncompressed(bitmap));
//                }
//                return new NanoHTTPD.Response(Response.Status.OK, "image/png",
//                        bitmapToInputStream(createBitmapFromText("No Icon")));
//            }

            // all other endpoints needs sessionId, so let's check it now
            if (TextUtils.isEmpty(sessionId)) {
                forbiddenCount++;
                Log.e(TAG, "/request_session should be requested first! URI:" + uri);
                return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                        createErrorJSONResponse("No Permission").toString());
            } else if (params.get("session") == null || !Objects.equals(params.get("session"), sessionId)) {
                forbiddenCount++;
                Log.e(TAG, "session incorrect! Permission denied");
                return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                        createErrorJSONResponse("Forbidden").toString());
            }

            // ok, we have permission. Let's proceed
            scheduleSessionExpire();

            if (method == Method.GET && uri.equals("/set_glance_dimension")) {
                int width = 200, height = 100, textHeight = 10;
                if (params.get("width") != null)
                    width = Integer.parseInt(Objects.requireNonNull(params.get("width")));
                if (params.get("height") != null)
                    height = Integer.parseInt(Objects.requireNonNull(params.get("height")));
                if (params.get("textHeight") != null)
                    textHeight = Integer.parseInt(Objects.requireNonNull(params.get("textHeight")));
                Log.d(TAG, "Glance view image will be of " + width + "x" + height + ", text height:" + textHeight);
                WatchNotification.setGlanceDimension(width, height, textHeight);

                // create JSON
                JSONObject json = new JSONObject();
                try {
                    json.put("session", sessionId);
                } catch (JSONException e) {
                    Log.e(TAG, "unable to create json object");
                    return new NanoHTTPD.Response(Response.Status.INTERNAL_ERROR, "application/json",
                            createErrorJSONResponse("unable to create response json").toString());
                }
                return new NanoHTTPD.Response(Response.Status.OK, "application/json", json.toString());
            }

            if (method == Method.GET && uri.equals("/notifications")) {
                notificationQueryCount++;
                // create JSON
                JSONObject json = new JSONObject();
                try {
                    JSONArray notif = new JSONArray();
                    for (final WatchNotification n : mNotifications) {
                        JSONObject o = new JSONObject();
                        o.put("id", n.id);
                        o.put("when", n.getWhen());
                        o.put("pages", n.getDetailBitmapCount());
                        notif.put(o);
                        Log.d(TAG, "[list]" + n.toLogString());
                    }
                    json.put("notification", notif);
                    json.put("timestamp", lastUpdatedTS);
                } catch (JSONException e) {
                    Log.e(TAG, "unable to create json object");
                    return new NanoHTTPD.Response(Response.Status.INTERNAL_ERROR, "application/json",
                            createErrorJSONResponse("unable to create response json").toString());
                }
                return new NanoHTTPD.Response(Response.Status.OK, "application/json", json.toString());
            }

            if (method == Method.DELETE && uri.startsWith("/notifications")) {
                String id = "";
                if (uri.length() > "/notifications/".length())
                    id = uri.substring("/notifications/".length());
                dismissQueryCount++;
                boolean all = id.equals("");
                for (final WatchNotification n : mNotifications) {
                    if (all) {
                        Log.d(TAG, "[dismiss]" + n.toLogString());
                        cancelNotification(n.key);
                        relayRemove(n.id);
                    } else if (n.id.equals(id)) {
                        Log.d(TAG, "[dismiss]" + n.toLogString());
                        // dismiss this notification
                        cancelNotification(n.key);
                        relayRemove(n.id);
                        mNotifications.remove(n);
                        lastNotificationWhen.remove(n.key);
                        // onNotificationRemoved will be called so there is no need to process mNotifications here
                        return new NanoHTTPD.Response(Response.Status.OK, "application/json",
                                createErrorJSONResponse("").toString());
                    }
                }
                if (all) {
                    mNotifications.clear();
                    return new NanoHTTPD.Response(Response.Status.OK, "application/json",
                            createErrorJSONResponse("").toString());
                }
                // error case
                return new NanoHTTPD.Response(Response.Status.BAD_REQUEST, "application/json",
                        createErrorJSONResponse("unknown id").toString());
            }

            forbiddenCount++;
            Log.e(TAG, "Forbidding " + uri + " (remote:" + session.getHeaders().get("remote-addr") + ")");
            return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                    createErrorJSONResponse("forbidden").toString());
        }
    }
}
