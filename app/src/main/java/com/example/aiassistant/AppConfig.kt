package com.example.aiassistant.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager
import com.example.aiassistant.R

/**
 * 全局配置中心 (单例对象)
 */
object AppConfig {

    // --- 全局可见的配置变量 ---
    lateinit var apiKey: String
    lateinit var modelName: String
    var micMode: String = "fill"

    // --- 讯飞 SparkChain 三元组 ---
    const val XUNFEI_APP_ID  = "bbd010e0"
    const val XUNFEI_API_KEY = "67e681ef755ea2aa428ea50a73fa0387"
    const val XUNFEI_API_SECRET = "NDEyZTk5OTFlMWZkMzk3Y2Q0ZTgyMTZm"

    // --- 内部逻辑 ---
    private const val DEFAULT_MODEL = "deepseek/deepseek-v3.2-251201"
    private const val PREFS_VERSION_KEY = "app_config_version"

    /**
     * 将监听器提升为 AppConfig 的成员变量。
     * 这会创建一个对监听器的强引用，防止它被垃圾回收。
     */
    private lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener

    /**
     * 初始化方法，必须在 Application.onCreate() 中调用一次。
     * @param context Application 上下文
     */
    fun init(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val storedVersionCode = prefs.getLong(PREFS_VERSION_KEY, -1L)
        val readAgain = storedVersionCode != currentVersionCode
        if (readAgain) {
            prefs.edit()
                .remove("key_api_key")
                .remove("key_model_name")
                .putLong(PREFS_VERSION_KEY, currentVersionCode)
                .apply()
        }
        PreferenceManager.setDefaultValues(context, R.xml.preferences, readAgain)

        // 1. 首次加载配置
        val storedApiKey = prefs.getString("key_api_key", "") ?: ""
        apiKey = if (storedApiKey.isBlank()) {
          
            "sk-a7ba8fe3a916194260b9765228b4c0a68281509b424a83e4274e6b517d4ff03e" // 仅在用户从未设置过时使用

        } else {
            storedApiKey
        }
        modelName = prefs.getString("key_model_name", DEFAULT_MODEL) ?: DEFAULT_MODEL
        micMode = prefs.getString("key_mic_mode", "fill") ?: "fill"

        // 2. 初始化并注册监听器
        listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            // 当任何一个 SharedPreferences 的值改变时，这个代码块会被触发
            when (key) {
                "key_api_key" -> {
                    apiKey = sharedPreferences?.getString(key, "") ?: ""
                }
                "key_model_name" -> {
                    modelName = sharedPreferences?.getString(key, DEFAULT_MODEL) ?: DEFAULT_MODEL
                }
                "key_mic_mode" -> {
                    micMode = sharedPreferences?.getString(key, "fill") ?: "fill"
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
}
