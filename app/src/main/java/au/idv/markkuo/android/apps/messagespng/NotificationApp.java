package au.idv.markkuo.android.apps.messagespng;

import android.graphics.drawable.Drawable;

public class NotificationApp {
    private String packageName;
    private String appName;
    private boolean enabled;
    private Drawable icon;

    public NotificationApp(String packageName, String appName, Drawable icon) {
        this.packageName = packageName;
        this.appName = appName;
        this.icon = icon;
        this.enabled = false;
    }

    public Drawable getAppIcon() {
        return icon;
    }

    public String getAppName() {
        return appName;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
