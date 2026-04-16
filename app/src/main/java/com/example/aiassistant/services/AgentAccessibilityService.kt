package com.example.aiassistant.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 在这里可以监听事件，但对于主动控制，我们主要依赖下面的方法
    }

    override fun onInterrupt() {
        // 服务中断时调用
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * 在屏幕指定坐标执行点击操作
     * @param x X坐标
     * @param y Y坐标
     * @return 操作是否成功派发
     */
    fun performGlobalClick(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return dispatchGestureAndAwait(gesture, timeoutMs = 1200)
    }

    /**
     * 在当前获得焦点的输入框中输入文本
     * @param text 要输入的文本
     * @return 操作是否成功
     */
    fun inputTextInFocusedField(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focusedNode != null && focusedNode.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }


    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    /**
     * 根据文本查找可访问性节点
     * @param text 要查找的文本
     * @return 找到的第一个节点，如果未找到返回null
     */
    fun findAccessibilityNodeInfoByText(text: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return if (nodes != null && nodes.isNotEmpty()) {
            nodes[0]
        } else {
            null
        }
    }

    /**
     * 执行节点点击操作
     * @param nodeInfo 要点击的节点
     * @return 操作是否成功
     */
    fun performClick(nodeInfo: AccessibilityNodeInfo): Boolean {
        return nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * 执行手势滑动
     * @param startX 起始X坐标
     * @param startY 起始Y坐标
     * @param endX 结束X坐标
     * @param endY 结束Y坐标
     * @param duration 滑动持续时间（毫秒）
     * @return 操作是否成功
     */
    fun performGesture(startX: Int, startY: Int, endX: Int, endY: Int, duration: Int): Boolean {
        val safeDuration = duration.coerceIn(300, 1800)
        val dx = endX - startX
        val dy = endY - startY
        val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
        if (distance < 80.0) {
            return false
        }

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, safeDuration.toLong()))
            .build()

        return dispatchGestureAndAwait(gesture, timeoutMs = safeDuration + 1500)
    }

    /**
     * 截取当前屏幕并返回位图。仅支持 Android 11+。
     */
    fun captureScreenBitmap(timeoutMs: Int = 3000): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        val bitmapRef = AtomicReference<Bitmap?>(null)
        val latch = CountDownLatch(1)

        val captureAction = {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            bitmapRef.set(hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false))
                        } catch (_: Exception) {
                            bitmapRef.set(null)
                        } finally {
                            hardwareBuffer.close()
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        bitmapRef.set(null)
                        latch.countDown()
                    }
                }
            )
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            captureAction()
        } else {
            Handler(Looper.getMainLooper()).post(captureAction)
        }

        val completed = latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        return if (completed) bitmapRef.get() else null
    }

    private fun dispatchGestureAndAwait(gesture: GestureDescription, timeoutMs: Int): Boolean {
        // Avoid deadlock: if caller is on main thread, we cannot block waiting for callback.
        // In this case, return whether the system accepted gesture dispatch.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return dispatchGesture(gesture, null, null)
        }

        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val accepted = AtomicBoolean(false)

        val dispatchAction = {
            val dispatchAccepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        completed.set(false)
                        latch.countDown()
                    }
                },
                null
            )

            accepted.set(dispatchAccepted)

            if (!dispatchAccepted) {
                completed.set(false)
                latch.countDown()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchAction()
        } else {
            Handler(Looper.getMainLooper()).post(dispatchAction)
        }

        return latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS) && accepted.get() && completed.get()
    }


}