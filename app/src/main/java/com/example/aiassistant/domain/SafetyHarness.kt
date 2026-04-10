package com.example.aiassistant.domain

import android.content.Context
import android.util.Log
import com.example.aiassistant.config.AppConfig
import com.example.aiassistant.services.AgentAccessibilityService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.ArrayDeque

/**
 * Runtime safety guard for tool calls. It validates high-risk actions before execution.
 */
object SafetyHarness {

    private const val TAG = "SafetyHarness"
    private val json = Json { ignoreUnknownKeys = true }

    private val clickTimestamps = ArrayDeque<Long>()
    private val backTimestamps = ArrayDeque<Long>()
    private val scrollTimestamps = ArrayDeque<Long>()

    private const val CLICK_WINDOW_MS = 20_000L
    private const val BACK_WINDOW_MS = 15_000L
    private const val SCROLL_WINDOW_MS = 20_000L

    private const val MAX_CLICKS_PER_WINDOW = 20
    private const val MAX_BACK_PER_WINDOW = 8
    private const val MAX_SCROLL_PER_WINDOW = 12

    private val blockedPackages = setOf(
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.systemui",
        "com.google.android.settings.intelligence"
    )

    private val sensitiveTextKeywords = listOf(
        "password", "passwd", "otp", "cvv", "bankcard", "cardnumber",
        "验证码", "密码", "支付密码", "银行卡", "银行卡号", "身份证"
    )

    data class Decision(
        val allowed: Boolean,
        val reason: String? = null
    )

    @Synchronized
    fun guard(toolName: String, rawArgs: String, context: Context): Decision {
        if (!AppConfig.safetyHarnessEnabled) {
            return Decision(allowed = true)
        }

        return when (toolName) {
            "launch_app" -> guardLaunchApp(rawArgs)
            "simulate_click" -> guardClick(rawArgs, context)
            "perform_back_press" -> guardBackPress()
            "scroll_screen" -> guardScroll()
            "input_text", "input_text_in_element" -> guardTextInput(rawArgs)
            else -> Decision(allowed = true)
        }
    }

    fun guardTextContent(text: String): Decision {
        if (!AppConfig.safetyHarnessEnabled) {
            return Decision(allowed = true)
        }

        val normalized = normalizeForRiskCheck(text)
        val hasSensitiveKeyword = sensitiveTextKeywords.any { normalized.contains(it) }
        val digitsOnly = normalized.filter { it.isDigit() }
        val hasLongDigit = digitsOnly.length >= 12

        if (hasSensitiveKeyword || hasLongDigit) {
            return Decision(
                allowed = false,
                reason = "安全拦截: 检测到疑似敏感信息输入，已阻止自动填充。"
            )
        }

        return Decision(true)
    }

    private fun guardLaunchApp(rawArgs: String): Decision {
        val packageName =
            parseString(rawArgs, "packageName")
                ?: parseString(rawArgs, "package_name")
                ?: parseString(rawArgs, "package")
                ?: return Decision(true)

        val normalizedPkg = normalizePackageName(packageName)
        if (isSensitivePackage(normalizedPkg)) {
            return Decision(
                allowed = false,
                reason = "安全拦截: 禁止自动启动敏感系统应用 $normalizedPkg。"
            )
        }
        return Decision(true)
    }

    fun isSensitivePackage(packageName: String): Boolean {
        val normalizedPkg = normalizePackageName(packageName)
        return blockedPackages.any { blocked ->
            normalizedPkg == blocked || normalizedPkg.startsWith("$blocked.")
        }
    }

    private fun normalizePackageName(raw: String): String {
        return raw.trim().lowercase().substringBefore('/')
    }

    @Synchronized
    private fun guardClick(rawArgs: String, context: Context): Decision {
        val x = parseInt(rawArgs, "x")
        val y = parseInt(rawArgs, "y")

        if (x != null && y != null && !isPointInSafeArea(x, y, context)) {
            return Decision(
                allowed = false,
                reason = "安全拦截: 点击坐标($x,$y)处于高风险边缘区域，请先观察界面后再操作。"
            )
        }

        if (isOverLimit(clickTimestamps, CLICK_WINDOW_MS, MAX_CLICKS_PER_WINDOW)) {
            return Decision(
                allowed = false,
                reason = "安全拦截: 点击频率异常，已触发防误触保护。"
            )
        }

        return Decision(true)
    }

    @Synchronized
    private fun guardBackPress(): Decision {
        if (isOverLimit(backTimestamps, BACK_WINDOW_MS, MAX_BACK_PER_WINDOW)) {
            return Decision(
                allowed = false,
                reason = "安全拦截: 返回操作过于频繁，已触发防退出保护。"
            )
        }
        return Decision(true)
    }

    @Synchronized
    private fun guardScroll(): Decision {
        if (isOverLimit(scrollTimestamps, SCROLL_WINDOW_MS, MAX_SCROLL_PER_WINDOW)) {
            return Decision(
                allowed = false,
                reason = "安全拦截: 滑动频率异常，已触发保护。"
            )
        }
        return Decision(true)
    }

    private fun guardTextInput(rawArgs: String): Decision {
        val text = parseString(rawArgs, "text") ?: return Decision(true)
        return guardTextContent(text)
    }

    private fun normalizeForRiskCheck(raw: String): String {
        // Remove whitespace and punctuation-like obfuscation, keep letters/digits only.
        return raw.lowercase().filter { it.isLetterOrDigit() }
    }

    private fun isPointInSafeArea(x: Int, y: Int, context: Context): Boolean {
        val service = AgentAccessibilityService.instance
        val width: Int
        val height: Int

        if (service != null) {
            val dm = service.resources.displayMetrics
            width = dm.widthPixels
            height = dm.heightPixels
        } else {
            val dm = context.resources.displayMetrics
            width = dm.widthPixels
            height = dm.heightPixels
        }

        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Invalid display size while checking safe area")
            return true
        }

        val marginX = (width * 0.03f).toInt()
        val marginY = (height * 0.05f).toInt()
        return x in marginX until (width - marginX) && y in marginY until (height - marginY)
    }

    private fun isOverLimit(queue: ArrayDeque<Long>, windowMs: Long, maxCount: Int): Boolean {
        val now = System.currentTimeMillis()
        while (queue.isNotEmpty() && now - queue.first() > windowMs) {
            queue.removeFirst()
        }
        queue.addLast(now)
        return queue.size > maxCount
    }

    private fun parseString(rawArgs: String, key: String): String? {
        return try {
            val obj = json.parseToJsonElement(rawArgs) as? JsonObject ?: return null
            obj[key]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    private fun parseInt(rawArgs: String, key: String): Int? {
        return try {
            val obj = json.parseToJsonElement(rawArgs) as? JsonObject ?: return null
            obj[key]?.jsonPrimitive?.intOrNull
        } catch (e: Exception) {
            null
        }
    }
}
