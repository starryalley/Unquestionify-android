package idv.markkuo.unquestionify;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.util.Base64;

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
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private ArrayList<WatchNotification> mNotifications;
    private Set<String> mAllowedSources;
    private long lastUpdatedTS = 0; //last updated time stamp for notification
    private final Map<String, Long> lastNotificationWhen = new HashMap<>();

    private String sessionId;
    private Handler sessionExpireHandler;

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
    private ConnectIQ.ConnectIQListener mCIQListener = new ConnectIQ.ConnectIQListener() {

        @Override
        public void onInitializeError(ConnectIQ.IQSdkErrorStatus errStatus) {
            Log.e(TAG, "initializing CIQ SDK error:" + errStatus.name());
            mCIQReady = false;
        }

        @Override
        public void onSdkReady() {
            Log.i(TAG, "initializing CIQ SDK done");
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

    private ConnectIQ.IQOpenApplicationListener mCIQOpenAppListener = new ConnectIQ.IQOpenApplicationListener() {
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
        return Charset.forName("US-ASCII").newEncoder().canEncode(v);
        // or "ISO-8859-1" for ISO Latin 1
    }

    private String textToBase64(String text) {

        float scale = getResources().getDisplayMetrics().density;

        // new anti-aliased Paint
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);

        // text size in pixels
        paint.setTextSize((int) (8 * scale));

        // text shadow
        //paint.setShadowLayer(1f, 0f, 1f, Color.DKGRAY);

        // draw text to the Canvas
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        Bitmap bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);

        //canvas.draw(icon);
        canvas.drawText(text, 0, bounds.height() - 1, paint);


        // create byte array
        // [15:0]   size (bytes) of payload
        // [31:16]  width
        // [47:32]  height
        //  ...     payload
        try {
            short size = (short) Math.ceil((double) bounds.width() * bounds.height() / 8);
            ByteBuffer bytes = ByteBuffer.allocate(size);
            Log.d(TAG, "creating " + (size) + " bytes of data");
            int k = 0;
            int pixel, intensity;
            byte b = 0;
            int bytecount = 0;
            for (int y = 0; y < bounds.height(); y++) {
                for (int x = 0; x < bounds.width(); x++, k++) {
                    // get one pixel color
                    pixel = bitmap.getPixel(x, y);
                    // pixel intensity.
                    intensity = (int) (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel));
                    if (k > 0 && k % 8 == 0) {
                        // save this byte
                        bytes.put(b);
                        b = 0;
                        bytecount++;
                    }
                    // binary
                    if (intensity < 128) {
                        b &= ~(1 << (k % 8));
                    } else {
                        b |= 1 << (k % 8);
                    }
                }
            }
            if (k % 8 != 0) {
                bytes.put(b);
                bytecount++;
            }

            JSONObject res = new JSONObject();
            try {
                res.put("width", bounds.width());
                res.put("height", bounds.height());
                res.put("data", Base64.encodeToString(bytes.array(), Base64.NO_WRAP));
            } catch (JSONException e) {
                Log.e(TAG, "unable to create json object");
            }
            Log.d(TAG, "width:" + bounds.width() + " height:" + bounds.height() + " =>raw " + bytes.array().length + " bytes, BASE64 encoded:" + res.getString("data").length() + " bytes");
            return res.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error creating byte array" + e);
            return "{}";
        }
    }

    private ByteArrayInputStream bitmapToInputStreamUncompressed(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return new ByteArrayInputStream(stream.toByteArray());
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

    private Bitmap createBitmapFromText(String text) {
        float scale = getResources().getDisplayMetrics().density;

        // new anti-aliased Paint
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);

        // text size in pixels
        paint.setTextSize((int) (6 * scale));

        // draw text to the Canvas
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        Bitmap bitmap = Bitmap.createBitmap(bounds.width(),bounds.height(),Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);

        //canvas.draw(icon);
        canvas.drawText(text, 0, bounds.height() - 1, paint);
        return bitmap;
    }

    private void loadAllowedApps() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        mAllowedSources = preferences.getStringSet("allowed_apps", null);
        if (mAllowedSources == null) {
            mAllowedSources = new HashSet<>();
            mAllowedSources.add("com.google.android.talk");//hangout
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
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
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
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
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
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return preferences.getBoolean("nonascii", false);
    }

    private boolean getGroupSimilarMessage() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return preferences.getBoolean("group_msg", false);
    }

    private void saveAllowedSources() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
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

        mNotifications = new ArrayList<>();

        loadAllowedApps();
        loadStatistics(getApplicationContext());

        // save default textsize setting
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (!preferences.contains("textsize")) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("textsize", Integer.toString(defaultTextSize));
            editor.apply();
        }

        // initialise CIQ
        mConnectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS);
        // Initialize the SDK
        mConnectIQ.initialize(this, true, mCIQListener);

        mCIQApp = new IQApp(CIQ_APP);

        // start server
        try {
            server = new NotificationHTTPD();
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "error starting httpd:" + e);
        }
    }

    @Override
    public void onDestroy() {
        saveStatistics(getApplicationContext());
        server.stop();
        try {
            mConnectIQ.shutdown(this);
        } catch (InvalidStateException e) {
            Log.w(TAG, "Cannot shutdown CIQ:" + e);
        }
        server.stop();
        super.onDestroy();
        unregisterReceiver(serviceReceiver);
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

    private void addNotification(StatusBarNotification sbn) {
        // ignore group summary and local-only messages
        if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0 ||
                (sbn.getNotification().flags & Notification.FLAG_LOCAL_ONLY) != 0) {
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
            Log.d(TAG, "[ignore] [" + sbn.getPackageName() + "]:" + sbn.getNotification().tickerText + " (flag:" + sbn.getNotification().flags + ")");
            return;
        }

        lastNotificationWhen.put(sbn.getKey(), sbn.getNotification().when);
        totalNotificationCount++;

        //use appending to existing notification if we want to group similar message
        if (getGroupSimilarMessage()) {
            for (int i = 0; i < mNotifications.size(); i++) {
                WatchNotification n = mNotifications.get(i);
                // similar message is defined as having the same StatusBarNotification key and same notification title
                if (n.key.equals(sbn.getKey()) && n.title.equals(getNotificationTitle(sbn))) {
                    // add to existing
                    n.appendMessage(notificationText, sbn.getNotification().when);
                    // move this to queue start
                    mNotifications.add(0, mNotifications.remove(i));
                    Log.d(TAG, "[append] [" + sbn.getPackageName() + "]:" + sbn.getNotification().tickerText + " (flag:" + sbn.getNotification().flags + ")");
                    lastUpdatedTS = System.currentTimeMillis();
                    startWatchApp();
                    return;
                }
            }
        }

        // doesn't exists, let's add to the beginning of the list
        Log.d(TAG, "[add] [" + sbn.getPackageName() + "]:" + sbn.getNotification().tickerText + " (flag:" + sbn.getNotification().flags + ")");
        mNotifications.add(0, new WatchNotification(getApplicationContext(), sbn.getKey(), getNotificationTitle(sbn),
                notificationText, getAppName(sbn.getPackageName()),
                sbn.getNotification().getSmallIcon(), sbn.getNotification().when));
        // sort mNotifications by when descendingly
        mNotifications.sort(Collections.reverseOrder(new Comparator<WatchNotification>() {
            @Override
            public int compare(WatchNotification a, WatchNotification b) {
                return Long.compare(a.when, b.when);
            }
        }));
        lastUpdatedTS = System.currentTimeMillis();
        startWatchApp();
    }

    private void removeNotification(StatusBarNotification sbn) {
        for (int i = 0; i < mNotifications.size(); i++) {
            WatchNotification n = mNotifications.get(i);
            if (n.key.equals(sbn.getKey()) && n.title.equals(getNotificationTitle(sbn))) {
                mNotifications.remove(i);
                lastNotificationWhen.remove(n.key);
                Log.d(TAG, "[remove] [" + sbn.getPackageName() + "]:" + sbn.getNotification().tickerText + " (flag:" + sbn.getNotification().flags + ")");
                lastUpdatedTS = System.currentTimeMillis();
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (!mAllowedSources.contains(sbn.getPackageName())) {
            return;
        }

        Log.v(TAG, "onNotificationPosted " + sbn.getNotification().tickerText + "\t" + getNotificationText(sbn) + "(" + sbn.getPackageName() + ")");
        // add this notification
        addNotification(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (mAllowedSources.contains(sbn.getPackageName())) {
            Log.v(TAG, "onNotificationRemoved " + sbn.getNotification().tickerText + "\t" + getNotificationText(sbn) + "(" + sbn.getPackageName() + ")");
            removeNotification(sbn);
        }
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

        // get current notification
        for (StatusBarNotification sbn : UnquestionifyService.this.getActiveNotifications()) {
            if (mAllowedSources.contains(sbn.getPackageName()))
                addNotification(sbn);
        }
    }

    private void startWatchApp() {
        if (!mCIQReady) {
            pendingStartApp = true;
            mConnectIQ.initialize(this, true, mCIQListener);
            Log.w(TAG, "CIQ not ready, re-initialise CIQ now");
            return;
        }
        if (mOpenAppRequestInProgress)
            return;
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
        } catch (Exception e) {
            Log.e(TAG, "openApplication failed:" + e);
        }
    }

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
            if(command.equals("addApp")) {
                //UnquestionifyService.this.cancelAllNotifications();
                String packageName = intent.getStringExtra("app");
                if (!packageName.equals("")) {
                    mAllowedSources.add(packageName);
                    Log.d(TAG, "adding " + packageName + " to allowed sources");
                    saveAllowedSources();
                }
            } else if (command.equals("removeApp")) {
                String packageName = intent.getStringExtra("app");
                if (!packageName.equals("")) {
                    mAllowedSources.remove(packageName);
                    Log.d(TAG, "removing " + packageName + " from allowed sources");
                    saveAllowedSources();
                }
            } else if (command.equals("startStatusReport")) {
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
            } else if (command.equals("stopStatusReport")) {
                if (statusReportHandler != null) {
                    Log.d(TAG, "stopping status report");
                    statusReportHandler.removeCallbacksAndMessages(null);
                    statusReportHandler = null;
                }
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

    // the HTTPD service for watch to read/dismiss notifications
    private class NotificationHTTPD extends NanoHTTPD {
        private String TAG = this.getClass().getSimpleName();

        public NotificationHTTPD() throws IOException {
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

            if (!session.getHeaders().get("remote-addr").equals("127.0.0.1")) {
                Log.e(TAG, "forbidding connection other than localhost. Incoming address:" + session.getHeaders().get("remote-addr"));
                forbiddenCount++;
                return new NanoHTTPD.Response(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "SERVICE FORBIDDEN");
            }

            Map<String, String> params = session.getParms();
            Map<String, String> headers = session.getHeaders();
            //Log.v(TAG, "=== [" + method.name() + "] URI:" + uri + ", Headers:" + headers + ", Params:" + params);

            // ========== request session ============
            if (method == Method.GET && uri.equals("/request_session")) {
                // check appid param
                if (params.get("appid") == null || !params.get("appid").equals(CIQ_APP)) {
                    Log.e(TAG, "/request_session requested with incorrect appid");
                    return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                            createErrorJSONResponse("Wrong appid").toString());
                }

                boolean isRound = true; //assume default watch is round shape
                int page = -1; // -1 is the overview page (max 3 lines)
                int watchWidth = 260, watchHeight = 260;
                if (params.get("width") != null)
                    watchWidth = Integer.parseInt(params.get("width"));
                if (params.get("height") != null)
                    watchHeight = Integer.parseInt(params.get("height"));
                if (params.get("shape") != null)
                    isRound = params.get("shape").equals("round");
                Log.d(TAG, "Watch Dimension:" + watchWidth + "x" + watchHeight + " pixels (round:" + isRound + ")");
                // calculate proper width/height for round watches
                if (isRound) {
                    // round screen, calculate max square inside the circle of diameter width
                    watchWidth = watchHeight = (int) Math.ceil(Math.sqrt(watchWidth * watchWidth / 2)) + defaultSquareWidthOffset;
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
                Log.e(TAG, "/request_session should be requested first!");
                return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                        createErrorJSONResponse("No Permission").toString());
            } else if (params.get("session") == null || !params.get("session").equals(sessionId)) {
                forbiddenCount++;
                Log.e(TAG, "session incorrect! Permission denied");
                return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                        createErrorJSONResponse("Forbidden").toString());
            }

            // ok, we have permission. Let's proceed
            scheduleSessionExpire();

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

            if (method == Method.GET && uri.startsWith("/notifications/")) {
                if (!connected) {
                    Log.e(TAG, "listener not ready yet");
                    return new NanoHTTPD.Response(Response.Status.OK, "image/png",
                            bitmapToInputStream(createBitmapFromText("Unavailable")));
                }

                int page = -1; // -1 is the overview page (max 3 lines)
                if (params.get("page") != null)
                    page = Integer.parseInt(params.get("page"));
                Log.d(TAG, "/notifications requested with page:" + page);
                bitmapQueryCount++;
                String id = uri.substring("/notifications/".length());
                if (mNotifications.size() == 0) {
                    return new NanoHTTPD.Response(Response.Status.OK, "image/png",
                            bitmapToInputStream(createBitmapFromText("No Notification")));
                }
                WatchNotification notification = null;
                for (final WatchNotification n : mNotifications) {
                    if (n.id.equals(id)) {
                        notification = n;
                        break;
                    }
                }
                if (notification == null) {
                    Log.e(TAG, "no notification for id:" + id);
                    return new NanoHTTPD.Response(Response.Status.OK, "image/png",
                            bitmapToInputStream(createBitmapFromText("Notification dismissed")));
                }

                // ok, now we want to create bitmap for notification
                Bitmap bitmap = null;
                if (page == -1)
                    bitmap = notification.getOverviewBitmap();
                else
                    bitmap = notification.getDetailBitmap(page);

                if (bitmap == null) {
                    Log.w(TAG, "request for image page " + page + " failed");
                    return new NanoHTTPD.Response(Response.Status.NO_CONTENT, "application/json",
                            createErrorJSONResponse("No such page").toString());
                }
                return new NanoHTTPD.Response(Response.Status.OK, "image/png", bitmapToInputStream(bitmap));
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
                    } else if (n.id.equals(id)) {
                        Log.d(TAG, "[dismiss]" + n.toLogString());
                        // dismiss this notification
                        cancelNotification(n.key);
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
