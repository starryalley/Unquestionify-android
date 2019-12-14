package au.idv.markkuo.android.apps.messagespng;

import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

public class NotificationSettingsFragment extends PreferenceFragmentCompat {
    private static final String TAG = NotificationSettingsFragment.class.getSimpleName();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings, rootKey);

        final SwitchPreferenceCompat nonAscIIPreference = findPreference("nonascii");
        if (nonAscIIPreference != null) {
            nonAscIIPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    Log.d(TAG, "Non-ASCII message only:" + newValue);
                    return true;
                }
            });
        }

        final EditTextPreference textSizePreference = findPreference("textsize");
        if (textSizePreference != null) {
            textSizePreference.setOnBindEditTextListener(
                    new EditTextPreference.OnBindEditTextListener() {
                        @Override
                        public void onBindEditText(EditText editText) {
                            editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                        }
                    });
            textSizePreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    Log.d(TAG, "text size changed to:" + newValue);
                    return true;
                }
            });
        }

        final Preference resetStatPreference = findPreference("resetStat");
        if (resetStatPreference != null) {
            resetStatPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Log.d(TAG, "Reset statistics");
                    MessagesPngService.resetStatistics(getActivity().getApplicationContext());
                    return true;
                }
            });
        }
    }

}