package com.example.aiassistant

import android.app.Application
import com.example.aiassistant.config.AppConfig

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppConfig.init(this)
    }
}
