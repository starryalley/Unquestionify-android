package idv.markkuo.unquestionify;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.Vector;

// abstraction of a notification
public class WatchNotification {
    private final String TAG = this.getClass().getSimpleName();

    private Bitmap summaryBitmap;
    private Bitmap glanceSummaryBitmap;
    private Bitmap overviewBitmap;
    private Vector<Bitmap> pageBitmaps;
    private boolean hasDetail = false;
    private static int width = 260, height = 260, glanceWidth = 200, glanceHeight = 100, glanceTextHeight = 10;
    private Context context;
    private Vector<String> msg;
    private String appName;
    private Icon icon;      //Notification.getSmallIcon()

    private static Locale locale;

    String id;      //random generated uuid for indexing
    String key;     //Notification.getKey()
    private String title;   //android.title
    long when;      //Notification.when

    static void setDimension(int watchWidth, int watchHeight) {
        width = watchWidth;
        height = watchHeight;
    }

    static void setGlanceDimension(int width, int height, int textHeight) {
        glanceWidth = width;
        glanceHeight = height;
        glanceTextHeight = textHeight;
    }

    WatchNotification(Context context, String key, String title, String text, String appName, Icon icon, long when) {
        pageBitmaps = new Vector<>();
        this.context = context;
        this.id = UUID.randomUUID().toString();
        this.key = key;
        this.title = title;
        this.msg = new Vector<>(4);
        this.appName = appName;
        this.icon = icon;
        appendMessage(text, when);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locale = context.getResources().getConfiguration().getLocales().get(0);
        } else {
            locale = context.getResources().getConfiguration().locale;
        }

    }

    private int getTextSize() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return Integer.parseInt(preferences.getString("textsize", "22"));
    }

    public @NonNull String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(appName).append("]\n").append(title);
        for (int i = Math.max((msg.size() - 3), 0); i < msg.size(); i++)
            sb.append("\n").append(msg.get(i));
        return sb.toString();
    }

    private String toSummaryString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(appName).append("] ").append(title);
        for (int i = Math.max((msg.size() - 3), 0); i < msg.size(); i++)
            sb.append("\n").append(msg.get(i).replaceAll("\n", "\t"));
        return sb.toString();
    }

    String toLogString() {
        return "[" + getWhen() + "] " + toString().replaceAll("\n", "\t");
    }

    void appendMessage(String text, long when) {
        // only show the latest 3 messages
        msg.add(text);
        if (msg.size() > 3) {
            msg.remove(0);
        }
        // re-build bitmap when necessary
        if (overviewBitmap != null) {
            _buildOverviewBitmap();
            _buildBitmaps();
        }
        this.when = when;
    }

    /*
    private static Bitmap drawableToBitmap(Drawable drawable) {
        Bitmap bitmap;

        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    Bitmap getIcon() {
        if (icon != null)
            return drawableToBitmap(icon.loadDrawable(context));
        return null;
    }
    */

    Bitmap getOverviewBitmap() {
        if (overviewBitmap == null)
            _buildOverviewBitmap();
        return overviewBitmap;
    }

    int getDetailBitmapCount() {
        // before we try to get detail bitmap, we should make sure overview bitmap is generated
        getOverviewBitmap();
        // if this notification has detail, let's build it now
        if (hasDetail && pageBitmaps.size() == 0)
            _buildBitmaps();
        return pageBitmaps.size();
    }

    Bitmap getDetailBitmap(int page) {
        if (hasDetail && pageBitmaps.size() == 0)
            _buildBitmaps();
        if (page >= 0 && page < pageBitmaps.size())
            return pageBitmaps.get(page);
        return null;
    }

    Bitmap getSummaryBitmap() {
        // always re-build summary bitmap
        _buildSummaryBitmap();
        return summaryBitmap;
    }

    String getWhen() {
        long now = System.currentTimeMillis();
        if (when != 0 && now > when) {
            long diff = now - when;
            int hour = (int) (diff/(1000 * 60 * 60));
            int min = (int) (diff/(1000*60)) % 60;
            if (hour == 0)
                return String.format(locale, "%d min ago", min);
            if (hour < 3)
                return String.format(locale, "%dh%2dm ago", hour, min);
        }
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(when));
    }

    private void _buildSummaryBitmap() {
        Log.d(TAG, "building summary bitmap of " + glanceWidth + "x" + glanceHeight + " text size:" + glanceTextHeight);
        Pair<Bitmap, Integer> p = _doTextLayout(toSummaryString(), glanceWidth, glanceHeight, 2, glanceTextHeight, false);
        summaryBitmap = p.first;
    }

    private void _buildOverviewBitmap() {
        Pair<Bitmap, Integer> p = _doTextLayout(toString(), width, height, 3, getTextSize(), true);
        hasDetail = !(p.second == 0);
        overviewBitmap = p.first;
    }

    private void _buildBitmaps() {
        pageBitmaps.clear();
        if (!hasDetail)
            return;
        //_buildBitmaps(toString(), width, height);
        int size = getTextSize();
        int maxLines = height / (size + 2) - 1; //TODO: i don't know the spacing, just use +2
        Log.d(TAG, "building detail message bitmaps...");
        String text = toString();

        while (true) {
            Pair<Bitmap, Integer> p = _doTextLayout(text, width, height, maxLines, size, true);
            pageBitmaps.add(p.first);
            // no next page
            if (p.second == 0)
                break;
            text = text.substring(p.second);
        }
        Log.d(TAG, "built " + pageBitmaps.size() + " detail message bitmaps");
    }

    // return bitmap and the next page's offset
    private Pair<Bitmap, Integer> _doTextLayout(String text, int width, int height, int maxLines, int textSize, boolean center) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        TextPaint textPaint=new TextPaint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);

        Rect rect = new Rect();
        textPaint.getTextBounds(text, 0, text.length(), rect);

        // paint black background
        canvas.drawColor(Color.BLACK);

        // do static text layout

        StaticLayout textLayout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, width)
                .setAlignment(center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(maxLines) // we need to calculate this properly for ellipsizing (ellipsis) to work
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();

        Log.v(TAG, "[_doTextLayout] width:" + width + ", height:" + height + ", max lines:" + maxLines);

        // get height of multiline text layout
        int layoutHeight = textLayout.getHeight();

        canvas.save();
        textLayout.draw(canvas);
        canvas.restore();

        int startLineTop = textLayout.getLineTop(0);
        int endLine = textLayout.getLineForVertical(startLineTop + height);
        int endLineBottom = textLayout.getLineBottom(endLine);

        int ellipsisStart = textLayout.getEllipsisStart(endLine);

        Log.v(TAG, "[_doTextLayout] endLine:" + endLine + ",endLineBottom:" + endLineBottom + ",end line ellipsis start:" + ellipsisStart);

        // there is next page (ellipsis is present)
        int offset = 0;
        if (endLine >= maxLines - 1 && ellipsisStart >= 0) {
            offset = textLayout.getLineStart(endLine) + ellipsisStart;
            if (textLayout.getEllipsisCount(endLine) > 0)
                Log.v(TAG, "[_doTextLayout] Leftover text offset:" + offset + " (total length:" + text.length() + ") " + text.substring(offset));
            else
                // all text is used up! offset 0 means no next page
                offset = 0;
        }

        // save bitmap
        // text is short which creates smaller image, let's create a smaller bitmap
        if (layoutHeight < height) {
            Log.v(TAG, "[_doTextLayout] return smaller bitmap of height:" + layoutHeight + "(original:" + height + ")");
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, layoutHeight);
        }

        return new Pair<>(bitmap, offset);
    }
}
