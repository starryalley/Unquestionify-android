package au.idv.markkuo.android.apps.messagespng;

import android.app.ListActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import au.idv.markkuo.android.apps.messagespng.adapter.NotificationSwitchAdapter;

public class NotificationListActivity extends AppCompatActivity {

    private static final String TAG = NotificationListActivity.class.getSimpleName();
    private NotificationSwitchAdapter adapter;
    private List<NotificationApp> list;
    private ListView listView;
    private Set<String> allowedApps;
    private PackageManager packageManager;
    private TextView loadingText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notificationlist);
        packageManager = getApplicationContext().getPackageManager();

        list = new ArrayList<>();
        adapter = new NotificationSwitchAdapter(this);
        listView = findViewById(R.id.list);
        listView.setAdapter(adapter);

        loadingText = findViewById(R.id.loading_text);

        // get allowed apps from current preference
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        allowedApps = sharedPreferences.getStringSet("allowed_apps", null);
        if (allowedApps == null) {
            Log.wtf(TAG, "allowed_apps should not be null");
        }
        // populate the listview
        new ListAppsTask().execute();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
    }


    public void updateEnabledApp(String appPackage, boolean enabled) {
        Intent intent = new Intent();
        intent.setAction("au.idv.markkuo.android.apps.messagespng.NOTIFICATION_LISTENER_SERVICE");
        if (enabled)
            intent.putExtra("command", "addApp");
        else
            intent.putExtra("command", "removeApp");
        intent.putExtra("app", appPackage);
        sendBroadcast(intent);
    }

    private void listApps() {
        //get a list of installed apps.
        List<ApplicationInfo> packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        packages.sort(new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return getAppName(a).compareToIgnoreCase(getAppName(b));
            }
        });

        for (ApplicationInfo packageInfo : packages) {
            //Log.d(TAG, "Installed app:[" + getAppName(packageInfo) + "]: " + packageInfo.packageName);
            if (((packageInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) &&
                    ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0)) {
                NotificationApp app = new NotificationApp(packageInfo.packageName, getAppName(packageInfo), getAppIcon(packageInfo.packageName));
                if (allowedApps != null && allowedApps.contains(packageInfo.packageName)) {
                    app.setEnabled(true);
                }
                list.add(app);
            }
        }
    }

    private String getAppName(ApplicationInfo ai) {
        return (String) (ai != null ? packageManager.getApplicationLabel(ai) : "(unknown)");
    }

    private Drawable getAppIcon(String packageName){
        Drawable drawable;
        try{
            drawable = packageManager.getApplicationIcon(packageName);
        }
        catch (PackageManager.NameNotFoundException e) {
            drawable = ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_launcher);
        }
        return drawable;
    }

    private class ListAppsTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... voids) {
            listApps();
            return null;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            list.clear();
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            loadingText.setVisibility(View.INVISIBLE);
            adapter.setApps(list);
        }
    }

}
