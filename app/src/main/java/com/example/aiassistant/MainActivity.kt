// 文件路径: app/src/main/java/com/example/aiassistant/ui/MainActivity.kt
package com.example.aiassistant.ui

import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.ChatAdapter
import com.example.aiassistant.ChatMessage // ADDED: 导入正确的UI模型
import com.example.aiassistant.R
import com.example.aiassistant.domain.AgentExecutionBus
import com.example.aiassistant.services.AgentAccessibilityService
import com.example.aiassistant.services.AgentForegroundService
import com.example.aiassistant.services.AsrManager
import com.example.aiassistant.utils.AccessibilityUtils
import kotlinx.coroutines.launch
import com.example.aiassistant.data.ChatMessage as ApiChatMessage // 使用别名区分API模型
import com.example.aiassistant.config.AppConfig

class MainActivity : AppCompatActivity() {

    // --- 视图变量 ---
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var settingsButton: ImageView
    private lateinit var exitButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var assistantLayout: ConstraintLayout
    private lateinit var rootContainer: View

    private val asrManager by lazy {
        AsrManager(
            this,
            onResult = { text ->
                if (AppConfig.micMode == "send") {
                    messageInput.setText(text)
                    messageInput.setSelection(text.length)
                    sendButton.performClick()
                } else {
                    messageInput.setText(text)
                    messageInput.setSelection(text.length)
                }
            },
            onError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // --- 数据和适配器 ---
    // MODIFIED: 使用正确的UI模型 ChatMessage，以匹配ChatAdapter的构造函数
    private val messageListForAdapter = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    // --- 无障碍相关 ---
    private var accessibilityDialog: AlertDialog? = null
    private var accessibilityObserver: ContentObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startAgentService()


        setupViews()
        setupRecyclerView()
        setupClickListeners()
        setupKeyboardListener()
        observeAgentBus()
        setupAccessibilityObserver()
        requestOverlayPermissionIfNeeded()
        AppConfig.init(this)
    }

    private fun startAgentService() {
        val intent = Intent(this, AgentForegroundService::class.java)
        startService(intent)
    }

    private fun setupViews() {
        rootContainer = findViewById(R.id.root_container)
        assistantLayout = findViewById(R.id.assistant_layout)
        chatRecyclerView = findViewById(R.id.chat_recycler_view)
        messageInput = findViewById(R.id.message_input)
        sendButton = findViewById(R.id.send_button)
        micButton = findViewById(R.id.mic_button)
        settingsButton = findViewById(R.id.settings_button)
        exitButton = findViewById(R.id.exit_button)
        accessibilityButton = findViewById(R.id.accessibility_button)
    }

    private fun setupRecyclerView() {
        // MODIFIED: 现在类型匹配，此行代码正确无误
        chatAdapter = ChatAdapter(messageListForAdapter)
        chatRecyclerView.adapter = chatAdapter
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupClickListeners() {
        settingsButton.setOnClickListener {
            //Toast.makeText(this, "设置按钮被点击", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        exitButton.setOnClickListener {
            showExitDialog()
        }

        accessibilityButton.setOnClickListener {
            AccessibilityUtils.openAccessibilitySettings(this)
        }

        sendButton.setOnClickListener {
            val userInput = messageInput.text.toString().trim()
            if (userInput.isNotBlank()) {
                lifecycleScope.launch {
                    AgentExecutionBus.userMessages.emit(userInput)
                }
                messageInput.text.clear()
            }
        }

        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        micButton.alpha = 0.5f
                        asrManager.startRecording()
                    } else {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(android.Manifest.permission.RECORD_AUDIO),
                            REQ_RECORD_AUDIO
                        )
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    micButton.alpha = 1.0f
                    asrManager.stopRecording()
                    true
                }
                else -> false
            }
        }

        messageInput.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || isEnterKey) {
                sendButton.performClick()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_confirm) { _, _ ->
                exitApp()
            }
            .setNegativeButton(R.string.exit_cancel, null)
            .show()
    }

    private fun exitApp() {
        stopService(Intent(this, AgentForegroundService::class.java))
        finishAffinity()
        Process.killProcess(Process.myPid())
    }





    private fun setupKeyboardListener() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboardHeight = imeInsets.bottom - systemBarInsets.bottom
            assistantLayout.translationY = -keyboardHeight.toFloat().coerceAtLeast(0f)
            insets
        }
    }

    private fun observeAgentBus() {
        lifecycleScope.launch {
            AgentExecutionBus.conversationUpdates.collect { apiMessages ->
                // 将后台的API模型(ApiChatMessage)映射为UI层的模型(ChatMessage)
                val uiMessages = apiMessages
                    .filter { it.role != "tool" && it.role != "system" }
                    .map { apiMsg ->
                        // MODIFIED: 创建正确的 ChatMessage 实例
                        ChatMessage(
                            message = apiMsg.content ?: "...",
                            isUser = apiMsg.role == "user"
                        )
                    }

                messageListForAdapter.clear()
                messageListForAdapter.addAll(uiMessages)
                chatAdapter.notifyDataSetChanged()

                if (messageListForAdapter.isNotEmpty()) {
                    chatRecyclerView.scrollToPosition(messageListForAdapter.size - 1)
                }
            }
        }
    }


    // --- 以下是无障碍服务相关的代码，保持不变 ---

    override fun onResume() {
        super.onResume()
        requestOverlayPermissionIfNeeded()
        if (!isA11yEnabled()) {
            showEnableAccessibilityDialog()
        } else {
            accessibilityDialog?.dismiss()
            accessibilityDialog = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        accessibilityObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        accessibilityDialog?.dismiss()
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    //用于检验无障碍服务是否开启
    private fun isA11yEnabled() =
        AccessibilityUtils.isAccessibilityServiceEnabled(
            this,
            AgentAccessibilityService::class.java
        )



    private fun showEnableAccessibilityDialog() {
        if (accessibilityDialog?.isShowing == true) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_enable_accessibility, null)
        accessibilityDialog = AlertDialog.Builder(this, R.style.CustomDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialogView.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            accessibilityDialog?.dismiss()
            accessibilityDialog = null
        }
        dialogView.findViewById<Button>(R.id.btn_go).setOnClickListener {
            AccessibilityUtils.openAccessibilitySettings(this)
        }
        accessibilityDialog?.show()
    }

    private fun setupAccessibilityObserver() {
        accessibilityObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                checkAndDismissDialog()
                if (isA11yEnabled()) accessibilityDialog?.dismiss()
            }
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            accessibilityObserver!!
        )
    }

    private fun checkAndDismissDialog() {
        if (isA11yEnabled())  {
            accessibilityDialog?.dismiss()
            accessibilityDialog = null

        }
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            refreshOverlay()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            refreshOverlay()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.overlay_permission_go) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton(R.string.exit_cancel, null)
            .show()
    }

    private fun refreshOverlay() {
        val intent = Intent(this, AgentForegroundService::class.java)
        intent.action = AgentForegroundService.ACTION_REFRESH_OVERLAY
        startService(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "麦克风权限已授予，请再次按住录音", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
    }
}

