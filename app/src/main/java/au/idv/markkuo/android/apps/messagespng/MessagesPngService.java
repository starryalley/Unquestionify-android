package au.idv.markkuo.android.apps.messagespng;

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
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
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

public class MessagesPngService extends NotificationListenerService {
    private final String TAG = this.getClass().getSimpleName();
    // for starting app on the watch
    private static final String CIQ_APP = "c569ccc1-be51-4860-bbcd-2b45a138d64b";
    private ConnectIQ mConnectIQ;
    private boolean mCIQReady = false;

    private IQDevice mCIQDevice;
    private IQApp mCIQApp;

    private boolean mOpenAppRequestInProgress = false;

    private MessagePngServiceReceiver serviceReceiver;
    private boolean connected = false;
    private NotificationHTTPD server;
    private ArrayList<WatchNotification> mNotifications;
    private Set<String> mAllowedSources;
    private long lastUpdatedTS = 0; //last updated time stamp for notification
    private final Map<String, Long> lastNotificationWhen = new HashMap<>();

    private String sessionId;

    // how many pixels to enlarge the square inside the circle
    // 40 is better but a long text on F6Pro can result in a 2500-byte png which is too big
    private static final int defaultSquareWidthOffset = 20;

    private ConnectIQ.IQDeviceEventListener mCIQDeviceEventListener = new ConnectIQ.IQDeviceEventListener() {

        @Override
        public void onDeviceStatusChanged(IQDevice device, IQDevice.IQDeviceStatus status) {
            Log.i(TAG, "CIQ Dev:" + device + " Status:" + status);
        }

    };

    private ConnectIQ.IQOpenApplicationListener mCIQOpenAppListener = new ConnectIQ.IQOpenApplicationListener() {
        @Override
        public void onOpenApplicationResponse(IQDevice device, IQApp app, ConnectIQ.IQOpenApplicationStatus status) {
            Log.i(TAG, "CIQ App status:" + status.name());
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


        /*
        Intent i = new Intent("au.idv.markkuo.garmin.picnotify.NOTIFICATION");
        i.putExtra("Bitmap", bitmap);
        //i.putExtra("monochrome", data);
        sendBroadcast(i);
         */
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

        int[] buf;
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
        Log.i(TAG, "Compressed PNG (" + reader.imgInfo.cols + "x" + reader.imgInfo.rows + "): " + bitmap.getByteCount() + " => " + baos.toByteArray().length + " bytes");
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
            saveSettings();
        }
        Log.d(TAG, "allowed app:" + mAllowedSources.toString());
    }

    private boolean getNonASCIIOnly() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return preferences.getBoolean("nonascii", false);
    }

    private void saveSettings() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = preferences.edit();
        editor.putStringSet("allowed_apps", mAllowedSources);
        Log.d(TAG, "saving allowed app:" + mAllowedSources.toString());
        editor.apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceReceiver = new MessagePngServiceReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("au.idv.markkuo.android.apps.messagespng.NOTIFICATION_LISTENER_SERVICE");
        registerReceiver(serviceReceiver, filter);

        mNotifications = new ArrayList<>();

        loadAllowedApps();

        // initialise CIQ
        mConnectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS);
        // Initialize the SDK
        mConnectIQ.initialize(this, true, new ConnectIQ.ConnectIQListener() {

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
            }

            @Override
            public void onSdkShutDown() {
                mCIQReady = false;
            }

        });

        // create watch app
        mCIQApp = new IQApp(CIQ_APP);

        // start server
        try {
            server = new NotificationHTTPD();
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "error starting httpd");
        }
    }

    @Override
    public void onDestroy() {
        try {
            mConnectIQ.unregisterAllForEvents();
            mConnectIQ.shutdown(this);
        } catch (InvalidStateException e) {
            Log.w(TAG, "Cannot shutdown CIQ:" + e);
        }
        server.stop();
        super.onDestroy();
        unregisterReceiver(serviceReceiver);
    }

    public void loadCIQDevices() {
        // Retrieve the list of known devices
        try {
            List<IQDevice> devices = mConnectIQ.getKnownDevices();
            if (devices != null) {
                Log.i(TAG, "CIQ devices count:" + devices.size());
                if (devices.size() > 0) {
                    mCIQDevice = devices.get(0);
                    Log.i(TAG, "CIQ Device:" + mCIQDevice.getFriendlyName());
                    mConnectIQ.registerForDeviceEvents(mCIQDevice, mCIQDeviceEventListener);
                }
            }
        } catch (InvalidStateException e) {
            // This generally means you forgot to call initialize(), but since
            // we are in the callback for initialize(), this should never happen
            Log.e(TAG, "register failed:" + e);
        } catch (ServiceUnavailableException e) {
            // This will happen if for some reason your app was not able to connect
            // to the ConnectIQ service running within Garmin Connect Mobile.  This
            // could be because Garmin Connect Mobile is not installed or needs to
            // be upgraded.
            Log.e(TAG, "register failed:" + e);
        }
    }

    private void addNotification(StatusBarNotification sbn) {
        // ignore group summary message
        if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            //Log.d(TAG, "[ignore]" + sbn.getNotification().tickerText + "\t" + getNotificationText(sbn) + "(" + sbn.getPackageName() + ")");
            return;
        }
        Long lastWhen = lastNotificationWhen.get(sbn.getKey());
        if(lastWhen != null && lastWhen >= sbn.getNotification().when){
            Log.d(TAG, "===> Ignore Old notification at " + sbn.getNotification().when + " Last:" + lastWhen);
            return;
        }
        // filter message content based on if it contains non-ascii chars
        boolean nonASCIIOnly = getNonASCIIOnly();
        String notificationText = getNotificationText(sbn);
        if (nonASCIIOnly && isPureAscii(notificationText)) {
            Log.d(TAG, "===> Ignore pure ASCII notification:" + notificationText);
            return;
        }

        lastNotificationWhen.put(sbn.getKey(), sbn.getNotification().when);

        for (int i = 0; i < mNotifications.size(); i++) {
            WatchNotification n = mNotifications.get(i);
            if (n.key.equals(sbn.getKey()) && n.title.equals(getNotificationTitle(sbn))) {
                // add to existing
                n.appendMessage(notificationText);
                // move this to queue start
                mNotifications.add(0, mNotifications.remove(i));
                Log.d(TAG, "[append] " + notificationText + "(" + sbn.getPackageName() + ")");
                Log.d(TAG, "==== " + sbn.toString());
                lastUpdatedTS = System.currentTimeMillis();
                return;
            }
        }
        // doesn't exists, let's add to the beginning of the list
        Log.d(TAG, "[add] " + sbn.getNotification().tickerText + "\t" + notificationText + "(" + sbn.getPackageName() + ")");
        mNotifications.add(0, new WatchNotification(getApplicationContext(), sbn.getKey(), getNotificationTitle(sbn),
                notificationText, getAppName(sbn.getPackageName()),
                sbn.getNotification().getSmallIcon()));
        lastUpdatedTS = System.currentTimeMillis();
    }

    private void removeNotification(StatusBarNotification sbn) {
        for (int i = 0; i < mNotifications.size(); i++) {
            WatchNotification n = mNotifications.get(i);
            if (n.key.equals(sbn.getKey()) && n.title.equals(getNotificationTitle(sbn))) {
                mNotifications.remove(i);
                lastNotificationWhen.remove(n.key);
                Log.d(TAG, "[remove] " + sbn.getNotification().tickerText + "\t" + getNotificationText(sbn) + "(" + sbn.getPackageName() + ")");
                lastUpdatedTS = System.currentTimeMillis();
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!mAllowedSources.contains(sbn.getPackageName())) {
            return;
        }

        Log.d(TAG, "onNotificationPosted " + sbn.getNotification().tickerText + "\t" + getNotificationText(sbn) + "(" + sbn.getPackageName() + ")");
        // add this notification
        addNotification(sbn);
        startWatchApp();

        /*
        // TODO: remove this
        Log.d(TAG, "===============");
        for (String key : sbn.getNotification().extras.keySet()) {
            if (sbn.getNotification().extras.get(key) != null)
                Log.d(TAG, key + "=" + sbn.getNotification().extras.get(key).toString());
        }
        */
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (mAllowedSources.contains(sbn.getPackageName())) {
            Log.d(TAG, "onNotificationRemoved " + sbn.getNotification().tickerText + "\t" + getNotificationText(sbn) + "(" + sbn.getPackageName() + ")");
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
        Log.i(TAG, "Listener connected");

        // get current notification
        for (StatusBarNotification sbn : MessagesPngService.this.getActiveNotifications()) {
            if (mAllowedSources.contains(sbn.getPackageName())) {
                addNotification(sbn);
            }
        }
    }

    private void startWatchApp() {
        if (!mCIQReady) {
            Log.w(TAG, "CIQ not ready, not starting watch app");
            return;
        }
        if (mOpenAppRequestInProgress)
            return;
        mOpenAppRequestInProgress = true;
        Log.d(TAG, "starting watch app");

        final Handler handler = new Handler();
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

    private class MessagePngServiceReceiver extends BroadcastReceiver{

        @Override
        public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra("command");
            if(command.equals("addApp")) {
                //MessagesPngService.this.cancelAllNotifications();
                String packageName = intent.getStringExtra("app");
                if (!packageName.equals("")) {
                    mAllowedSources.add(packageName);
                    Log.d(TAG, "adding " + packageName + " to allowed sources");
                    saveSettings();
                }
            } else if (command.equals("removeApp")) {
                String packageName = intent.getStringExtra("app");
                if (!packageName.equals("")) {
                    mAllowedSources.remove(packageName);
                    Log.d(TAG, "removing " + packageName + " from allowed sources");
                    saveSettings();
                }
            } else if (command.equals("setTextSize")) {
                int size = intent.getIntExtra("textsize", 22);
                saveSettings();
            }
            //TODO: add more
        }
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
            // TODO: re-enable this check after development finishes
//            if (!session.getHeaders().get("remote-addr").equals("127.0.0.1")) {
//                Log.e(TAG, "forbidding connection other than localhost. Incoming address:" + session.getHeaders().get("remote-addr"));
//                return new NanoHTTPD.Response(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "SERVICE FORBIDDEN");
//            }

            Map<String, String> params = session.getParms();
            Map<String, String> headers = session.getHeaders();
            Log.v(TAG, "=== [" + method.name() + "] URI:" + uri + ", Headers:" + headers + ", Params:" + params);//TODO: remove this

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
                // calculate proper width/height for round watches
                if (isRound) {
                    // round screen, calculate max square inside the circle of diameter width
                    watchWidth = watchHeight = (int) Math.ceil(Math.sqrt(watchWidth * watchWidth / 2)) + defaultSquareWidthOffset;
                }
                Log.d(TAG, "Png will be of " + watchWidth + "x" + watchHeight + " pixels");
                WatchNotification.setDimesion(watchWidth, watchHeight);

                // update sessionId
                sessionId = UUID.randomUUID().toString();
                Log.i(TAG, "Session started");
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

            //TODO: testing only, move to the end
            if (method == Method.GET && uri.startsWith("/icons/")) {
                if (mNotifications.size() > 0) {
                    Bitmap bitmap = mNotifications.get(0).getIcon();
                    if (bitmap != null)
                        return new NanoHTTPD.Response(Response.Status.OK, "image/png", bitmapToInputStreamUncompressed(bitmap));
                }
                return new NanoHTTPD.Response(Response.Status.OK, "image/png",
                        bitmapToInputStream(createBitmapFromText("No Icon")));
            }

            // all other endpoints needs sessionId, so let's check it now
            if (TextUtils.isEmpty(sessionId)) {
                Log.e(TAG, "/request_session should be requested first!");
                return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                        createErrorJSONResponse("No Permission").toString());
            } else if (params.get("session") == null || !params.get("session").equals(sessionId)) {
                Log.e(TAG, "session incorrect! Permission denied");
                return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                        createErrorJSONResponse("Forbidden").toString());
            }

            // ok, we have permission. Let's proceed

            if (method == Method.GET && uri.equals("/notifications")) {
                // create JSON
                JSONObject json = new JSONObject();
                try {
                    JSONArray notif = new JSONArray();
                    for (final WatchNotification n : mNotifications) {
                        notif.put(n.id);
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

                //Bitmap bitmap = createBitmapFromTextAutoLayout(notification.toString(), width, height, page);
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

            if (method == Method.GET && uri.startsWith("/notifications_details/")) {
                if (!connected) {
                    Log.e(TAG, "listener not ready yet");
                    return new NanoHTTPD.Response(Response.Status.OK, "application/json",
                            createErrorJSONResponse("Unavailable").toString());
                }

                String id = uri.substring("/notifications_details/".length());
                // do cache
                WatchNotification notification = null;
                for (final WatchNotification n : mNotifications) {
                    if (n.id.equals(id)) {
                        notification = n;
                        break;
                    }
                }
                if (notification == null) {
                    Log.e(TAG, "no notification for id:" + id);
                    return new NanoHTTPD.Response(Response.Status.BAD_REQUEST, "application/json",
                            createErrorJSONResponse("no such notification").toString());
                }

                JSONObject json = new JSONObject();
                try {
                    json.put("pageCount", notification.getDetailBitmapCount());
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



            Log.e(TAG, "Forbidding " + uri + " (remote:" + session.getHeaders().get("remote-addr") + ")");
            return new NanoHTTPD.Response(Response.Status.FORBIDDEN, "application/json",
                    createErrorJSONResponse("forbidden").toString());
        }
    }
}
