package au.idv.markkuo.android.apps.messagespng;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.util.UUID;
import java.util.Vector;

// abstraction of notification here
public class WatchNotification {
    private final String TAG = this.getClass().getSimpleName();
    private static final int defaultTextSize = 20;
    private Bitmap overviewBitmap;
    private Vector<Bitmap> pageBitmaps;
    private static int width = 260, height = 260;

    String id;      //random generated uuid for indexing
    String key;     //Notification.getKey()
    String title;   //android.title

    private Vector<String> msg;    //android.text
    private String appName; //
    private Icon icon;      //Notification.getSmallIcon()

    public static void setDimesion(int watchWidth, int watchHeight) {
        width = watchWidth;
        height = watchHeight;
    }

    WatchNotification(String key, String title, String text, String appName, Icon icon) {
        pageBitmaps = new Vector<>();
        this.id = UUID.randomUUID().toString();
        this.key = key;
        this.title = title;
        this.msg = new Vector<>(4);
        this.appName = appName;
        this.icon = icon;
        appendMessage(text);
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
        // UPDATE: it's handled in caller now
        // do not add this message if it is the same as the last message
        //if (msg.size() > 0 && msg.get(msg.size() - 1).equals(text))
        //    return;
        // only show the latest 3 messages
        msg.add(text);
        if (msg.size() > 3) {
            msg.remove(0);
        }
        //
        // re-build overview bitmap when necessary
        if (overviewBitmap != null)
            _buildOverviewBitmap();
        // re-build bitmap when we have ever built it
        if (pageBitmaps.size() > 0)
            _buildBitmaps();
    }

    Bitmap getOverviewBitmap() {
        // lazy
        if (overviewBitmap == null)
            _buildOverviewBitmap();
        return overviewBitmap;
    }

    int getDetailBitmapCount() {
        // lazy initialisation
        if (pageBitmaps.size() == 0)
            _buildBitmaps();
        return pageBitmaps.size();
    }

    Bitmap getDetailBitmap(int page) {
        // lazy initialisation
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
        int maxLines = height / (defaultTextSize + 2) - 1; //TODO: i don't know the spacing, just use +2
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
        final int textSize = 20;

        TextPaint textPaint=new TextPaint();
        //textPaint.setTextAlign(TextPaint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);

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
        Log.v(TAG, "=============");
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
        if (endLine >= maxLines - 1 && ellipsisStart > 0) {
            offset = textLayout.getLineStart(endLine) + ellipsisStart;
            Log.d(TAG, "[_doTextLayout] Leftover text offset:" + offset);
        }

        // save bitmap
        // text is short which creates smaller image, let's create a smaller bitmap
        if (layoutHeight < height) {
            Log.d(TAG, "[_doTextLayout] return smaller bitmap of height:" + layoutHeight + "(original:" + height + ")");
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, layoutHeight);
        }

        Pair<Bitmap, Integer> bitmapIntegerPair = new Pair<>(bitmap, offset);
        return bitmapIntegerPair;
    }
}
