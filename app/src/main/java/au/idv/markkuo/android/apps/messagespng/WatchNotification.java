package au.idv.markkuo.android.apps.messagespng;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.preference.PreferenceManager;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.util.UUID;
import java.util.Vector;

// abstraction of a notification
public class WatchNotification {
    private final String TAG = this.getClass().getSimpleName();
    private static final int defaultTextSize = 24; //24 is better for F6pro, 20 is fine on vivoactive 4s
    private Bitmap overviewBitmap;
    private Vector<Bitmap> pageBitmaps;
    private static int width = 260, height = 260;
    private Context context;
    private Vector<String> msg;
    private String appName;
    private Icon icon;      //Notification.getSmallIcon()

    String id;      //random generated uuid for indexing
    String key;     //Notification.getKey()
    String title;   //android.title

    static void setDimension(int watchWidth, int watchHeight) {
        width = watchWidth;
        height = watchHeight;
    }

    WatchNotification(Context context, String key, String title, String text, String appName, Icon icon) {
        pageBitmaps = new Vector<>();
        this.context = context;
        this.id = UUID.randomUUID().toString();
        this.key = key;
        this.title = title;
        this.msg = new Vector<>(4);
        this.appName = appName;
        this.icon = icon;
        appendMessage(text);
    }

    private int getTextSize() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return Integer.parseInt(preferences.getString("textsize", Integer.toString(defaultTextSize)));
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(appName).append("]\n").append(title);
        for (int i = (msg.size() - 3) < 0 ? 0 : msg.size() - 3; i < msg.size(); i++)
            sb.append("\n").append(msg.get(i));
        return sb.toString();
    }

    String toLogString() {
        return toString().replaceAll("\n", "\t");
    }

    void appendMessage(String text) {
        // only show the latest 3 messages
        msg.add(text);
        if (msg.size() > 3) {
            msg.remove(0);
        }
        // re-build overview bitmap when necessary
        if (overviewBitmap != null)
            _buildOverviewBitmap();
        // re-build bitmap when we have ever built it
        if (pageBitmaps.size() > 0)
            _buildBitmaps();
    }

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

    Bitmap getOverviewBitmap() {
        if (overviewBitmap == null)
            _buildOverviewBitmap();
        return overviewBitmap;
    }

    int getDetailBitmapCount() {
        if (pageBitmaps.size() == 0)
            _buildBitmaps();
        return pageBitmaps.size();
    }

    Bitmap getDetailBitmap(int page) {
        if (pageBitmaps.size() == 0)
            _buildBitmaps();
        if (page >= 0 && page < pageBitmaps.size())
            return pageBitmaps.get(page);
        return null;
    }

    private void _buildOverviewBitmap() {
        Pair<Bitmap, Integer> p = _doTextLayout(toString(), width, height, 3);
        overviewBitmap = p.first;
    }

    private void _buildBitmaps() {
        pageBitmaps.clear();
        //_buildBitmaps(toString(), width, height);
        int maxLines = height / (getTextSize() + 2) - 1; //TODO: i don't know the spacing, just use +2
        Log.d(TAG, "building detail message bitmaps...");
        String text = toString();
        while (true) {
            Pair<Bitmap, Integer> p = _doTextLayout(text, width, height, maxLines);
            pageBitmaps.add(p.first);
            // no next page
            if (p.second == 0)
                break;
            text = text.substring(p.second);
        }
        Log.d(TAG, "built " + pageBitmaps.size() + " detail message bitmaps");
    }

    // return bitmap and the next page's offset
    private Pair<Bitmap, Integer> _doTextLayout(String text, int width, int height, int maxLines) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        TextPaint textPaint=new TextPaint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(getTextSize());

        Rect rect = new Rect();
        textPaint.getTextBounds(text, 0, text.length(), rect);

        // paint black background
        canvas.drawColor(Color.BLACK);

        // do static text layout
        int layoutHeight = height;
        StaticLayout textLayout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(maxLines) // we need to calculate this properly for ellipsizing (ellipsis) to work
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        Log.v(TAG, "[_doTextLayout] max lines:" + maxLines);

        // get height of multiline text layout
        layoutHeight = textLayout.getHeight();

        canvas.save();
        textLayout.draw(canvas);
        canvas.restore();

        int startLineTop = textLayout.getLineTop(0);
        int endLine = textLayout.getLineForVertical(startLineTop + height);
        int endLineBottom = textLayout.getLineBottom(endLine);
        int maxLinesOnpage = endLine;

        int ellipsisStart = textLayout.getEllipsisStart(endLine);
        Log.v(TAG, "[_doTextLayout] endLine:" + endLine + ",endLineBottom:" + endLineBottom +
                ",maxLinesOnPage:" + maxLinesOnpage + ",endline ellipsis start:" + ellipsisStart);

        // there is next page (ellipsis is present)
        int offset = 0;
        if (endLine >= maxLines - 1 && ellipsisStart >= 0) {
            offset = textLayout.getLineStart(endLine) + ellipsisStart;
            if (offset < text.length() - 1)
                Log.v(TAG, "[_doTextLayout] Leftover text offset:" + offset);
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

        Pair<Bitmap, Integer> bitmapIntegerPair = new Pair<>(bitmap, offset);
        return bitmapIntegerPair;
    }
}
