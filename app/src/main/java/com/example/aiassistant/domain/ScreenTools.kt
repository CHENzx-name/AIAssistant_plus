// 文件路径: app/src/main/java/com/example/aiassistant/domain/ScreenTools.kt
package com.example.aiassistant.domain

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aiassistant.services.AgentAccessibilityService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.accessibilityservice.AccessibilityService

// 1. 定义一个数据类来存储UI元素的信息
// @Serializable 注解让它可以被 kotlinx.serialization 库自动转换为JSON
@Serializable
data class UiElement(
    val id: Int, // 一个简单的唯一标识符
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: String // 元素的边界，格式为 "[left,top][right,bottom]"
)

object ScreenTools {

    // 缓存上一次屏幕分析的结果
    private var lastAnalyzedElements: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 分析当前屏幕，提取所有可交互的UI元素。
     * 这是 "observe_screen" 工具的后端实现。
     */
    fun analyzeScreen(): String {
        val service = AgentAccessibilityService.instance ?: return "错误: 无障碍服务未连接。"
        val rootNode = service.rootInActiveWindow ?: return "错误: 无法获取屏幕根节点。"

        val elements = mutableListOf<UiElement>()
        val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()
        var elementIdCounter = 0

        // 递归遍历节点树
        traverseNode(rootNode) { node ->
            // 筛选出我们认为可交互的元素
            if (isInteractable(node)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                val element = UiElement(
                    id = elementIdCounter,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    bounds = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
                )
                elements.add(element)
                nodeMap[elementIdCounter] = node // 将ID和节点关联起来
                elementIdCounter++
            }
        }

        // 更新缓存
        lastAnalyzedElements = nodeMap

        // 将元素列表序列化为JSON字符串返回给AI
        return json.encodeToString(elements)
    }

    /**
     * 根据ID点击一个元素。
     * 这是 "click_element" 工具的后端实现。
     */
    fun clickElementById(elementId: Int): String {
        val service = AgentAccessibilityService.instance ?: return "错误: 无障碍服务未连接。"
        val nodeToClick = lastAnalyzedElements[elementId]
            ?: return "错误: 找不到ID为 $elementId 的元素。请先调用 observe_screen 来刷新元素列表。"

        // 优先使用节点自带的点击动作
        if (nodeToClick.isClickable) {
            val success = nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return if (success) "成功点击了ID为 $elementId 的元素。" else "错误: 无法通过动作点击ID为 $elementId 的元素。"
        }

        // 备用方案：通过坐标点击
        val bounds = Rect()
        nodeToClick.getBoundsInScreen(bounds)
        val success = service.performGlobalClick(bounds.centerX(), bounds.centerY())
        return if (success) "成功点击了ID为 $elementId 的元素（通过坐标）。" else "错误: 无法通过坐标点击ID为 $elementId 的元素。"
    }

    /**
     * 在指定ID的元素中输入文本。
     * 这是 "input_text_in_element" 工具的后端实现。
     */
    fun inputTextInElementById(elementId: Int, text: String): String {
        val service = AgentAccessibilityService.instance ?: return "错误: 无障碍服务未连接。"
        val nodeToInput = lastAnalyzedElements[elementId]
            ?: return "错误: 找不到ID为 $elementId 的元素。请先调用 observe_screen 来刷新元素列表。"

        val decision = SafetyHarness.guardTextContent(text)
        if (!decision.allowed) {
            return decision.reason ?: "安全拦截: 当前输入被阻止。"
        }

        if (nodeToInput.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = nodeToInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            return if (success) "成功在ID为 $elementId 的元素中输入文本。" else "错误: 无法在ID为 $elementId 的元素中输入文本。"
        }

        return "错误: ID为 $elementId 的元素不可编辑。"
    }


    // --- 辅助函数 ---

    /**
     * 递归遍历辅助函数
     */
    private fun traverseNode(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, action)
            }
        }
    }

    /**
     * 判断一个节点是否是用户可能希望交互的节点
     */
    private fun isInteractable(node: AccessibilityNodeInfo): Boolean {
        // 如果节点本身不可见，则直接跳过
        if (!node.isVisibleToUser) {
            return false
        }

        // 有明确的文本、内容描述，或可点击、可编辑，通常是可交互的
        val hasText = !node.text.isNullOrEmpty()
        val hasContentDesc = !node.contentDescription.isNullOrEmpty()
        val isClickable = node.isClickable
        val isEditable = node.isEditable

        // 过滤掉那些非常小的、可能只是布局用的不可见元素
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val isLargeEnough = bounds.width() > 20 && bounds.height() > 20

        return (hasText || hasContentDesc || isClickable || isEditable) && isLargeEnough
    }


    fun goToHome(): String {
        val service = AgentAccessibilityService.instance ?: return "错误: 无障碍服务未连接。"

        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        return if (success) "成功返回主页面。" else "错误: 无法返回主页面。"
    }


}
