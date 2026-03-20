package com.example.aiassistant.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.aiassistant.services.AgentAccessibilityService
import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.app.ActivityManager
import android.os.SystemClock
import android.view.MotionEvent  // 同时确保MotionEvent的导入
import androidx.core.graphics.component1
import androidx.core.graphics.component2
import androidx.core.graphics.component3
import androidx.core.graphics.component4
import android.app.Instrumentation
import android.graphics.Point
import android.view.Display
import android.view.WindowManager
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import com.example.aiassistant.domain.AgentExecutionBus
import kotlin.math.max
import kotlin.math.min
/**
 * 存放需要特殊权限的系统级工具
 */
object SystemTools {

    fun simulateClick(x: Int, y: Int): String {
        val success = AgentAccessibilityService.instance?.performGlobalClick(x, y)
        return if (success == true) "坐标($x, $y)点击成功。" else "坐标($x, $y)点击失败，无障碍服务可能未连接。"
    }

    fun inputText(text: String): String {
        val success = AgentAccessibilityService.instance?.inputTextInFocusedField(text)
        return if (success == true) "文本 '$text' 输入成功。" else "输入失败，未找到活动的输入框。"
    }

    fun performBackPress(): String {
        val service = AgentAccessibilityService.instance ?: return "错误: 无障碍服务未连接。"

        // 使用无障碍服务执行全局返回动作
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        return if (success) "成功执行了返回操作。" else "错误: 执行返回操作失败。"
    }

    /**
     * 根据提供的包名启动一个安卓应用。
     * @param context 上下文环境，用于启动 Activity。
     * @param packageName 要启动的应用的完整包名 (例如 "com.android.settings")。
     * @return 描述操作结果的字符串。
     */
    fun launchApp(context: Context, packageName: String): String = try {

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
            // ★ 关键：清栈 + 新任务
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or     // 清掉旧栈
                        Intent.FLAG_ACTIVITY_CLEAR_TOP         // 可选，进一步保证
            )
            // 再次声明 MAIN / LAUNCHER，兼容部分定制 ROM
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        if (launchIntent != null) {
            context.startActivity(launchIntent)
            "应用 $packageName 已成功启动（已重置至主页面）。"
        } else {
            "错误：未找到包名为 $packageName 的应用。"
        }

    } catch (e: Exception) {
        Log.e("SystemTools", "启动应用失败: $packageName", e)
        "错误：启动应用 $packageName 时发生异常。"
    }


    fun getInstalledApps(context: Context): String {
        return try {
            val pm = context.packageManager
            // 过滤出那些有启动意图的应用，并提取其包名
            val appPackages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { it.packageName }

            if (appPackages.isNotEmpty()) {
                // 将列表格式化为换行分隔的字符串
                "已获取到以下可启动的应用包名：\n${appPackages.joinToString("\n")}"
            } else {
                "未找到任何可启动的应用。"
            }
        } catch (e: Exception) {
            Log.e("SystemTools", "获取应用列表失败", e)
            "错误：获取应用列表时发生异常。"
        }
    }

    fun returnToHomeScreen(context: Context): String {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return "已执行返回主屏幕操作。"
    }

    /**
     * 滑动屏幕（优化版：增加屏幕边界自适应和完整异常处理）
     * 支持上下左右四个方向，根据屏幕尺寸自动调整滑动距离
     * @param direction 滑动方向，可选值："up"（上滑）、"down"（下滑）、"left"（左滑）、"right"（右滑）
     * @param distance 滑动像素距离（正数）
     * @param duration 执行时间（毫秒）
     * @return 操作结果描述
     */
    fun scrollScreen(direction: String, distance: Int, duration: Int): String {
        // 参数校验
        if (distance <= 0) {
            return "错误：滑动距离必须为正数"
        }
        if (duration <= 0) {
            return "错误：执行时间必须为正数"
        }
        val normalizedDirection = when (direction.trim().lowercase()) {
            "up", "上", "上滑", "向上", "向上滑" -> "up"
            "down", "下", "下滑", "向下", "向下滑" -> "down"
            "left", "左", "左滑", "向左", "向左滑" -> "left"
            "right", "右", "右滑", "向右", "向右滑" -> "right"
            else -> direction.trim().lowercase()
        }
        val validDirections = listOf("up", "down", "left", "right")
        if (normalizedDirection !in validDirections) {
            return "错误：方向必须是以下值之一：${validDirections.joinToString()}"
        }

        val service = AgentAccessibilityService.instance ?: return "错误: 无障碍服务未连接。"

        try {
            // 获取屏幕尺寸
            val context = service.applicationContext
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return "错误：无法获取窗口管理器"

            val display = windowManager.defaultDisplay
            val size = Point()
            display.getRealSize(size)
            val screenWidth = size.x
            val screenHeight = size.y

            val marginX = (screenWidth * 0.08f).toInt().coerceAtLeast(1)
            val marginY = (screenHeight * 0.08f).toInt().coerceAtLeast(1)
            val topSafeY = (screenHeight * 0.15f).toInt()
            val bottomSafeY = (screenHeight * 0.85f).toInt()
            val leftSafeX = (screenWidth * 0.15f).toInt()
            val rightSafeX = (screenWidth * 0.85f).toInt()
            val maxVertical = (bottomSafeY - topSafeY).coerceAtLeast(120)
            val maxHorizontal = (rightSafeX - leftSafeX).coerceAtLeast(120)
            val overlayBounds = AgentExecutionBus.overlayBounds.value
            val baseDuration = duration.coerceIn(350, 1600)

            // 自适应边界：根据方向限制最大滑动距离
            val actualDistance = when (normalizedDirection) {
                "up", "down" -> distance.coerceAtMost(maxVertical).coerceAtLeast(180)
                "left", "right" -> distance.coerceAtMost(maxHorizontal).coerceAtLeast(180)
                else -> distance
            }

            var attempts = 0
            val maxAttempts = 4

            when (normalizedDirection) {
                "up", "down" -> {
                    val xCandidates = buildLaneCandidatesX(screenWidth, marginX, overlayBounds)
                    for (x in xCandidates) {
                        if (attempts >= maxAttempts) break
                        // Direction uses content scroll semantics:
                        // down => finger swipes up, up => finger swipes down.
                        val startY = if (normalizedDirection == "down") bottomSafeY else topSafeY
                        val endY = if (normalizedDirection == "down") {
                            (startY - actualDistance).coerceAtLeast(topSafeY)
                        } else {
                            (startY + actualDistance).coerceAtMost(bottomSafeY)
                        }

                        if (isVerticalPathBlocked(x, startY, endY, overlayBounds)) continue

                        val attemptDuration = baseDuration + attempts * 140
                        val success = service.performGesture(x, startY, x, endY, attemptDuration)
                        attempts++
                        if (success) {
                            return "成功${normalizedDirection}滑屏幕，距离：$actualDistance 像素，耗时：$attemptDuration 毫秒，第${attempts}次命中"
                        }
                        SystemClock.sleep(120)
                    }
                }
                "left", "right" -> {
                    val yCandidates = buildLaneCandidatesY(screenHeight, marginY, overlayBounds)
                    for (y in yCandidates) {
                        if (attempts >= maxAttempts) break
                        val startX = if (normalizedDirection == "left") rightSafeX else leftSafeX
                        val endX = if (normalizedDirection == "left") {
                            (startX - actualDistance).coerceAtLeast(leftSafeX)
                        } else {
                            (startX + actualDistance).coerceAtMost(rightSafeX)
                        }

                        if (isHorizontalPathBlocked(y, startX, endX, overlayBounds)) continue

                        val attemptDuration = baseDuration + attempts * 140
                        val success = service.performGesture(startX, y, endX, y, attemptDuration)
                        attempts++
                        if (success) {
                            return "成功${normalizedDirection}滑屏幕，距离：$actualDistance 像素，耗时：$attemptDuration 毫秒，第${attempts}次命中"
                        }
                        SystemClock.sleep(120)
                    }
                }
                else -> return "错误：无效的方向"
            }

            return "${normalizedDirection}滑失败：已尝试${attempts}次轨迹，系统未完成手势"

        } catch (e: Exception) {
            return "${normalizedDirection}滑失败：${e.message ?: "未知错误"}"
        }
    }

    private fun buildLaneCandidatesX(screenWidth: Int, marginX: Int, overlay: Rect?): List<Int> {
        val lanes = listOf(0.30f, 0.50f, 0.70f)
            .map { (screenWidth * it).toInt().coerceIn(marginX, screenWidth - marginX) }
            .toMutableList()
        lanes.add(pickSafeX(screenWidth, marginX, overlay))
        return lanes.distinct()
    }

    private fun buildLaneCandidatesY(screenHeight: Int, marginY: Int, overlay: Rect?): List<Int> {
        val lanes = listOf(0.30f, 0.50f, 0.70f)
            .map { (screenHeight * it).toInt().coerceIn(marginY, screenHeight - marginY) }
            .toMutableList()
        lanes.add(pickSafeY(screenHeight, marginY, overlay))
        return lanes.distinct()
    }

    private fun isVerticalPathBlocked(x: Int, startY: Int, endY: Int, overlay: Rect?): Boolean {
        if (overlay == null) return false
        if (x < overlay.left || x > overlay.right) return false
        val pathTop = min(startY, endY)
        val pathBottom = max(startY, endY)
        return rangesOverlap(pathTop, pathBottom, overlay.top, overlay.bottom)
    }

    private fun isHorizontalPathBlocked(y: Int, startX: Int, endX: Int, overlay: Rect?): Boolean {
        if (overlay == null) return false
        if (y < overlay.top || y > overlay.bottom) return false
        val pathLeft = min(startX, endX)
        val pathRight = max(startX, endX)
        return rangesOverlap(pathLeft, pathRight, overlay.left, overlay.right)
    }

    private fun rangesOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean {
        return aStart <= bEnd && bStart <= aEnd
    }

    private fun pickSafeX(screenWidth: Int, marginX: Int, overlay: Rect?): Int {
        if (overlay == null) return screenWidth / 2
        val leftRangeStart = marginX
        val leftRangeEnd = (overlay.left - marginX).coerceAtLeast(leftRangeStart)
        val rightRangeStart = (overlay.right + marginX).coerceAtMost(screenWidth - marginX)
        val rightRangeEnd = (screenWidth - marginX).coerceAtLeast(rightRangeStart)

        val leftWidth = leftRangeEnd - leftRangeStart
        val rightWidth = rightRangeEnd - rightRangeStart

        return when {
            rightWidth > leftWidth && rightWidth > 0 -> (rightRangeStart + rightRangeEnd) / 2
            leftWidth > 0 -> (leftRangeStart + leftRangeEnd) / 2
            else -> screenWidth / 2
        }
    }

    private fun pickSafeY(screenHeight: Int, marginY: Int, overlay: Rect?): Int {
        if (overlay == null) return screenHeight / 2
        val topRangeStart = marginY
        val topRangeEnd = (overlay.top - marginY).coerceAtLeast(topRangeStart)
        val bottomRangeStart = (overlay.bottom + marginY).coerceAtMost(screenHeight - marginY)
        val bottomRangeEnd = (screenHeight - marginY).coerceAtLeast(bottomRangeStart)

        val topHeight = topRangeEnd - topRangeStart
        val bottomHeight = bottomRangeEnd - bottomRangeStart

        return when {
            bottomHeight > topHeight && bottomHeight > 0 -> (bottomRangeStart + bottomRangeEnd) / 2
            topHeight > 0 -> (topRangeStart + topRangeEnd) / 2
            else -> screenHeight / 2
        }
    }
}