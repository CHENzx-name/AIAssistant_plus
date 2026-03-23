package com.example.aiassistant.ui

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.example.aiassistant.R

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        
        // 处理说明书管理的点击事件
        findPreference<Preference>("key_manuals")?.setOnPreferenceClickListener {
            val intent = Intent(context, ManualsActivity::class.java)
            startActivity(intent)
            true
        }
    }
}
