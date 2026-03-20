// 文件路径: app/src/main/java/com/example/aiassistant/services/AgentForegroundService.kt
package com.example.aiassistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.aiassistant.R
import com.example.aiassistant.domain.AgentExecutionBus
import com.example.aiassistant.ui.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentForegroundService : Service() {

    // 创建一个与服务生命周期绑定的协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 在服务内部持有ViewModel实例，使其生命周期与服务同步
    private lateinit var viewModel: ChatViewModel
    private var floatingOverlay: FloatingChatOverlay? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "AgentServiceChannel"
        const val ACTION_REFRESH_OVERLAY = "com.example.aiassistant.action.REFRESH_OVERLAY"
    }

    override fun onCreate() {
        super.onCreate()
        // 实例化ViewModel，它现在是服务的一部分
        viewModel = ChatViewModel()

        // 启动协程来监听来自UI的用户消息
        serviceScope.launch {
            AgentExecutionBus.userMessages.collect { userInput ->
                withContext(Dispatchers.Main) {
                    floatingOverlay?.setAutomationMode(true)
                }
                // 将Service的Context传递给ViewModel，用于需要Context的工具
                viewModel.sendMessage(userInput, this@AgentForegroundService)
            }
        }

        // 启动协程来监听ViewModel的状态更新，并将其广播给UI
        serviceScope.launch {
            viewModel.apiMessages.collect { messages ->
                AgentExecutionBus.conversationUpdates.tryEmit(messages)
                val assistantMessages = messages
                    .filter { it.role == "assistant" }
                    .mapNotNull { it.content?.trim()?.takeIf { c -> c.isNotBlank() } }
                    .takeLast(8)
                withContext(Dispatchers.Main) {
                    floatingOverlay?.updateMessages(assistantMessages)
                }
            }
        }

        serviceScope.launch {
            AgentExecutionBus.automationActive.collect { active ->
                withContext(Dispatchers.Main) {
                    floatingOverlay?.setAutomationMode(active)
                }
            }
        }


        ensureOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_OVERLAY) {
            ensureOverlay()
        }
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        // 返回START_STICKY，确保服务在被系统杀死后会尝试重启
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // 当服务被销毁时，取消所有正在运行的协程，防止内存泄漏
        serviceScope.cancel()
        floatingOverlay?.dismiss()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 我们使用事件总线进行通信，因此不需要绑定，返回null
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "AI Agent Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Agent 正在运行")
            .setContentText("智能助理服务在后台为您待命")
            .setSmallIcon(R.mipmap.ic_launcher) // 请确保您有这个图标资源
            .build()
    }

    private fun ensureOverlay() {
        if (floatingOverlay != null) return
        if (!Settings.canDrawOverlays(this)) return
        floatingOverlay = FloatingChatOverlay(
            this,
            onStop = { viewModel.stopCurrentWork() },
            onBoundsChanged = { bounds ->
                AgentExecutionBus.overlayBounds.value = bounds
            }
        )
        floatingOverlay?.show()
        floatingOverlay?.setMessageScrollable(true)
        floatingOverlay?.setAutomationMode(AgentExecutionBus.automationActive.value)
    }
}