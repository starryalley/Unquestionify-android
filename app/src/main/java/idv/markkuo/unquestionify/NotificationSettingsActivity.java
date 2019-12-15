package idv.markkuo.unquestionify;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class NotificationSettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (findViewById(R.id.fragment_container) != null) {

            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new NotificationSettingsFragment())
                    .commit();
        }
    }
}