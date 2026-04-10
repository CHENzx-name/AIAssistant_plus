// 文件路径: app/src/main/java/com/example/aiassistant/ui/ChatViewModel.kt
package com.example.aiassistant.ui



import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiassistant.data.ChatCompletionRequest
import com.example.aiassistant.data.ChatRepository
import com.example.aiassistant.data.FunctionDescription
import com.example.aiassistant.data.FunctionParameters
import com.example.aiassistant.data.ParameterProperty
import com.example.aiassistant.data.Tool
import com.example.aiassistant.data.ToolCall
import com.example.aiassistant.domain.LocalTools.getCurrentTime
import com.example.aiassistant.domain.LocalTools.getCurrentWeather
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context
import com.example.aiassistant.data.ClickParams
import com.example.aiassistant.data.TextParams
import com.example.aiassistant.data.ScrollParams
import com.example.aiassistant.domain.SystemTools
import com.example.aiassistant.domain.AgentExecutionBus
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlinx.serialization.builtins.*
import kotlinx.serialization.Serializable
import com.example.aiassistant.domain.ScreenTools
import com.example.aiassistant.domain.SafetyHarness
import com.example.aiassistant.data.LaunchAppParams
import com.example.aiassistant.data.FunctionProperty
import androidx.preference.PreferenceManager

import com.example.aiassistant.config.AppConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.charset.StandardCharsets
import android.util.Log

class ChatViewModel : ViewModel() {

    // 1. 初始化数据仓库，用于网络请求
    private val repository = ChatRepository()

    private val json = Json { ignoreUnknownKeys = true }
    // 2. 内部状态，使用为API定义的复杂ChatMessage模型 (com.example.aiassistant.data.ChatMessage)
    private val _apiMessages = MutableStateFlow<List<com.example.aiassistant.data.ChatMessage>>(emptyList())
    // 3. 暴露给UI (MainActivity) 的只读状态流
    val apiMessages = _apiMessages.asStateFlow()

    // 4. 维护完整的对话历史，包括用户、助手和工具的交互
    private val conversationHistory = mutableListOf<com.example.aiassistant.data.ChatMessage>()

    // 当前会话的执行任务，用于支持手动停止
    private var activeJob: Job? = null

    // 5. 定义模型可用的工具列表
    private val availableTools by lazy {
        listOf(
            Tool(
                type = "function",
                function = FunctionDescription(
                    name = "get_current_time",
                    description = "当你想知道现在的时间时非常有用。",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                )
            ),
            Tool(
                type = "function",
                function = FunctionDescription(
                    name = "get_current_weather",
                    description = "当你想查询指定城市的天气时非常有用。",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "location" to ParameterProperty(
                                type = "string",
                                description = "城市或县区，比如北京市、杭州市、余杭区等。"
                            )
                        ),
                        required = listOf("location")
                    )
                )
            ),
            Tool(
                 type = "function",
                 function = FunctionDescription(
                 name = "simulate_click",
                 description = "模拟在屏幕的指定 x 和 y 坐标上进行一次点击。坐标原点 (0,0) 在屏幕左上角。",
                 parameters = FunctionParameters(
                    type = "object",
                    properties = mapOf(
                        "x" to ParameterProperty(
                            type = "integer",
                            description = "要点击位置的 x 坐标"
                        ),
                        "y" to ParameterProperty(
                            type = "integer",
                            description = "要点击位置的 y 坐标"
                        )
                    ),
                    required = listOf("x", "y")
                )
            )
        ),

        Tool(
            type = "function",
            function = FunctionDescription(
                name = "scroll_screen",
                description = "模拟在当前前台屏幕进行上下左右滑动。方向支持 up/down/left/right，可选距离和时长。",
                parameters = FunctionParameters(
                    type = "object",
                    properties = mapOf(
                        "direction" to ParameterProperty(
                            type = "string",
                            description = "滑动方向：up/down/left/right"
                        ),
                        "distance" to ParameterProperty(
                            type = "integer",
                            description = "滑动距离（像素），可选，默认 800"
                        ),
                        "duration" to ParameterProperty(
                            type = "integer",
                            description = "滑动持续时间（毫秒），可选，默认 300"
                        )
                    ),
                    required = listOf("direction")
                )
            )
        ),

        // 3. 输入文字工具
        Tool(
            type = "function",
            function = FunctionDescription(
                name = "input_text",
                description = "在当前屏幕上拥有焦点的输入框中输入指定的文本。如果当前没有活动的输入框，则该操作可能会失败。",
                parameters = FunctionParameters(
                    type = "object",
                    properties = mapOf(
                        "text" to ParameterProperty(
                            type = "string",
                            description = "要输入到焦点输入框中的文字内容。"
                        )
                    ),
                    required = listOf("text")
                )
            )
        ) ,
        Tool(
            type = "function",
            function = FunctionDescription(
            name = "list_available_manuals",
            description = "获取所有可用的APP说明书列表。当用户问题可能与某个APP操作有关，但不确定是哪个APP时使用。",
            parameters = FunctionParameters(
                type = "object",
                properties = emptyMap<String, ParameterProperty>(), // 显式指定类型
                required = emptyList()
            )
            )
            ),
        Tool(
            type = "function",
            function = FunctionDescription(
                name = "get_manual_section",
                description = "从指定的说明书中，根据关键词获取特定操作或功能的详细说明章节。",
                parameters = FunctionParameters(
                    type = "object",
                    properties = mapOf(
                        "manual_name" to ParameterProperty(
                            type = "string",
                            description = "说明书的文件名, 例如 'photo_editor_manual.md'。必须从 list_available_manuals 工具的返回结果中选择。"
                        ),
                        "section_query" to ParameterProperty(
                            type = "string",
                            description = "描述用户想要查询的操作的关键词, 例如 '裁剪', '导出', '转换视频'。"
                        )
                    ),
                    required = listOf("manual_name", "section_query")
                )
            )
        ),

            Tool(
                type = "function",
                function = FunctionDescription(
                    name = "observe_screen",
                    description = "分析当前手机屏幕，并以JSON格式返回所有可交互的UI元素列表。每个元素都有一个唯一的 'id'，用于后续的点击或输入操作。",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                )
            ),
            Tool(
                type = "function",
                function = FunctionDescription(
                    name = "click_element",
                    description = "根据 'observe_screen' 返回的元素 'id' 点击一个UI元素。",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "element_id" to ParameterProperty(
                                type = "integer",
                                description = "从 'observe_screen' 获取到的目标元素的 'id'。"
                            )
                        ),
                        required = listOf("element_id")
                    )
                )
            ),
            Tool(
                type = "function",
                function = FunctionDescription(
                    name = "input_text_in_element",
                    description = "在 'observe_screen' 返回的指定 'id' 的输入框元素中输入文本。",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "element_id" to ParameterProperty(
                                type = "integer",
                                description = "从 'observe_screen' 获取到的目标输入框元素的 'id'。"
                            ),
                            "text" to ParameterProperty(
                                type = "string",
                                description = "要输入的文本内容。"
                            )
                        ),
                        required = listOf("element_id", "text")
                    )
                )
            ),
            Tool(
                type = "function",
                function = FunctionDescription(
                    name = "perform_back_press",
                    description = "执行一个全局返回操作，等同于用户点击了系统导航栏的返回按钮或使用了返回手势。",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = emptyMap(), // 无需参数
                        required = emptyList()
                    )
                )
            ),
            Tool(
                type = "function",
                function = FunctionDescription(
                    name = "launch_app",
                    description = "启动一个指定的安卓应用。你需要提供该应用的标准包名。",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            // 正如您所指出的，这里应该使用 ParameterProperty
                            "packageName" to ParameterProperty(
                                type = "string",
                                description = "要启动的应用的包名，例如 'com.android.settings' 或 'com.android.chrome',包名参数使用get_installed_apps工具获得所有安装包名"
                            )
                        ),
                        required = listOf("packageName")
                    )
                )
            ),
            Tool(
                type = "function",
                function = FunctionDescription(
                    name = "get_installed_apps",
                    description = "获取手机上安装的所有应用程序的包名列表",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(),
                        required = listOf()
                    )
                )
            ),
            Tool(
                type = "function",
                function = FunctionDescription(
                    name = "return_to_home_screen",
                    description = "返回到安卓设备的主屏幕（桌面）。",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = emptyMap(), // 此工具不需要参数
                        required = emptyList()
                    )
                )
            )
        )
    }


    /**
     * 当ViewModel第一次被创建时执行
     */
    init {
        val systemMessage = com.example.aiassistant.data.ChatMessage(
            role = "system",
            content = """
            你是一个名叫“小智”的个人助理，所有问题都必须先走工具流程查询后再回答。
            不要直接回答任何问题，必须先调用合适的工具获取信息或执行操作，再基于工具结果进行回复。
            当用户意图模糊但可能涉及APP操作时，你可以主动补全意图并执行必要的APP操作，不必等待用户逐步确认。
            未经用户明确要求，禁止调用 `perform_back_press`，并避免在主界面触发返回操作。
            当用户意图指向某类任务（如查攻略、订票、看天气、查路线等）但未指定APP时，先调用 `get_installed_apps`，在结果中选择最匹配的APP并直接 `launch_app`，无需再次询问。

            你的任务是
            - 帮助用户查询各种APP的操作说明书。
            工作流程指引：
            1.  当用户要你操作某个APP时，你必须首先使用 `list_available_manuals` 工具来获取当前所有可用的说明书列表。
            2.  然后，根据用户的提问和上一步返回的列表，选择一个最匹配的说明书文件名。
            3.  最后，使用 `get_manual_section` 工具并传入正确的文件名来查询具体章节。
            - 帮助用户打开各种APP
            工作流程指引：
            1. 当用户让你打开某个app,你需要先查询手机安装的所有APP,之后选择一个最匹配的app作为参数,使用app启动工具打开
            - 帮助用户完成app操作
            1,当用户要求你在app操作时,首先找到一个叫功能列表的说明书,那里有你可以实现的功能,知道功能后在相应的app说明书中查具体功能,再执行,执行时如果没有找到相应按钮,等待一段时间重新试试
            **重要规则：禁止在未确认文件存在的情况下，直接猜测并使用 `get_manual_section` 工具,所有的app操作必须基于说明书,注意说明书是给你看的,除非用户要求输出说明书,不然你就直接按照说明书执行动作，如果遇到异常情况，返回到聊天页面**
          工作流程指引：
            1.触发条件：用户点击“一键统一回复”按钮或输入“回复未读消息”等指令时启动。

            2.未读检测（细化APP特征）：
             - QQ（com.tencent.mobileqq）：未读标识为消息列表中对话左侧的红色数字/圆点。
             - 微信（com.tencent.mm）：未读标识为对话名称旁的红色数字/“未读”标签。
             - 抖音（com.ss.android.ugc.aweme）：未读标识为底部导航栏第4个“消息图标”（对话气泡样式）右上角的红色数字/圆点；进入消息列表后，未读对话左侧有红色小圆点。
             - 小红书（com.xiaohongshu.android）：未读标识为底部导航栏第四个“消息图标”上的红色圆点；进入消息列表后，未读对话右侧有红色圆点。

            3. 回复执行（分平台细化步骤）：
             - 通用步骤：对有未读的APP，调用 `launch_app` 打开，进入消息列表。
             - 分平台操作：
             - 抖音：
                a. 进入抖音后，`observe_screen` 定位底部导航栏第4个“消息图标”（特征：右上角红标），`click_element` 点击（验证：界面顶部显示“消息”标题）。
                b. 消息列表中，未读对话特征必须满足：右侧红色圆点；`observe_screen` 定位第一条未读对话的“完整条目区域”（含头像、名称、消息预览，宽占满屏幕，高约80-100像素）。
                c. 点击区域：必须点击该条目的“中间偏左位置”（覆盖头像和名称区域；`click_element` 点击后，等待1秒，`observe_screen` 验证是否进入聊天界面（特征：底部出现“发送消息”输入框）。
                d. 发送消息（强制点击逻辑）：
   - 第一步：输入内容并等待按钮激活  
     调用 `input_text_in_element` 向输入框填入回复内容后，**强制等待1秒**（而非0.5秒，确保按钮有足够时间从灰色→红色）。  

   - 第二步：精准定位发送按钮（特征硬约束）  
     调用 `observe_screen` 工具，必须找到同时满足以下所有特征的元素：  
     ① 位置：与输入框**同一水平线上**，紧贴输入框右侧边缘（间距≤5像素）；  
     ② 颜色：**纯红色**（RGB值接近#FF3B30，区别于其他灰色/蓝色元素）；  
     ③ 形状：**向右的箭头图标**（箭头尖端朝右，可能是空心或实心，尺寸约20×20像素）；  
     ④ 状态：可点击（`observe_screen` 返回 `clickable=true`）。  
     若未找到，立即重试 `observe_screen`（最多2次，每次间隔0.5秒，扩大识别范围至输入框右侧100像素区域）。  

   - 第三步：强制执行点击  
     找到符合特征的发送按钮后，调用 `click_element` 工具点击，**点击后必须保持界面停留0.5秒**（避免因系统延迟导致点击无效）。  

   - 第四步：双重验证发送结果  
     调用 `observe_screen` 检查：  
     ① 输入框是否已清空（内容消失）；  
     ② 聊天记录最底部是否出现刚发送的消息（内容、颜色与输入一致）。  
     只有同时满足①+②，才判定为“发送成功”。  

   - 第五步：失败重试机制  
     若验证失败（输入框未空/消息未出现）：  
     1. 第1次重试：重新定位发送按钮（可能因界面微动导致位置偏移），再次点击；  
     2. 第2次重试：点击发送按钮后，额外调用 `perform_enter_key` 工具（模拟回车发送，作为备选方案）；  
     3. 仍失败：记录“该条消息发送失败（内容：XXX）”，继续处理下一条，最终反馈用户。  
                e. 发送回复后，`perform_back_press` 返回消息列表，继续下一条。识别逻辑：若连续3条均无红色圆点，判定为“当前无未读”，停止遍历。

            - 小红书：
    a. 进入小红书后，`observe_screen` 定位底部导航栏第四个“消息图标”（右上角红标），`click_element` 点击（验证：界面顶部显示“消息”标题）。
    b. 消息列表中，未读对话特征必须满足：右侧红色圆点；`observe_screen` 定位第一条未读对话的“完整条目区域”（含头像、名称、消息预览，宽占满屏幕，高约80-100像素）。
                c. 点击区域：必须点击该条目的“中间偏左位置”（覆盖头像和名称区域；`click_element` 点击后，等待1秒，`observe_screen` 验证是否进入聊天界面（特征：底部出现“发消息”输入框）。
    d. 调用 `input_text_in_element` 向输入框填入回复内容（完成后**强制等待1.2秒**，确保与输入框水平最右侧按钮从灰色变为**深红色可点击状态**）；  
       调用 `observe_screen` 定位发送按钮（必须同时满足：① 与输入框同水平线最右侧；② 深红色“纸飞机”图标或“发送”文字；③ 距离输入框右侧边缘3-8像素），获取其 `element_id`；  
       调用 `click_element` 点击该发送按钮（点击后**停留0.8秒**，确保系统接收点击指令）；  
       必须验证：`observe_screen` 确认① 输入框内容完全清空；② 聊天记录最底部出现刚发送的消息（内容匹配），两者同时满足才判定为发送成功。  
    e. 若验证失败（未清空/消息未出现）：  
       1. 第1次重试：重新定位发送按钮（可能因界面微动偏移），再次点击；  
       2. 第2次重试：点击发送按钮后，额外调用 `perform_enter_key` 工具（模拟回车发送）；  
       3. 仍失败：记录“该条消息发送失败（内容：XXX）”，继续处理下一条；  
       发送成功/重试后，`perform_back_press` 返回消息列表，继续下一条。  
    f. 若连续3条对话均无左侧红色圆点，判定为“当前无未读”，停止遍历。
            - 微信：（保留原有逻辑，重点确保未读对话点击“条目中间区域”）
            - QQ：
  a. 进入QQ后，`observe_screen` 定位顶部“消息”标签（通常在界面顶部左侧，文字为“消息”，可能带红色未读数字 badge），`click_element` 点击进入消息列表（验证：界面显示所有对话条目，顶部有“搜索”框）。
  
  b. 消息列表中，未读对话特征必须同时满足（强制校验）：
     ① 未读标识：对话条目左侧或右侧有**红色数字标签**（显示未读数量，如“2”）或**红色实心圆点**（无数量时），数字标签背景为纯红色，文字为白色；
     ② 排序规则：未读对话按接收时间倒序排列，**一定位于已读对话上方**，且通常在列表前5条范围内；
     ③ 条目结构：包含“左侧头像（圆形）+ 中间名称/消息预览 + 右侧时间/未读标识”，高度约90-110像素，宽度占满屏幕；
     `observe_screen` 必须定位同时满足①+③的第一条条目，记录其完整区域（左上角x1,y1 - 右下角x2,y2），不满足则判定为“无未读”。
  
  c. 点击区域与验证（精准点击逻辑）：
     - 点击坐标：在步骤b定位的条目区域内，取**横向中间偏左40%、纵向中间位置**（即：x = x1 + (x2-x1)*40%，y = y1 + (y2-y1)*50%），此位置覆盖“头像右侧的名称区域”（QQ的核心可点击热区）；
     - 执行点击：调用 `click_element` 点击上述坐标，点击后**等待1秒**（QQ界面切换较快，1秒足够加载）；
     - 双重验证是否进入聊天界面：
       ① 必选项：`observe_screen` 检测到底部出现“输入框”（右侧可能有“+”号图标和“表情”图标）；
       ② 辅助项：界面顶部显示联系人/群聊名称（与步骤b中的对话名称一致），且顶部有“返回”箭头（左侧）和“更多”图标（右侧）；
       必须满足①，才判定为“进入成功”。
     - 若验证失败（未出现输入框/停留在消息列表）：
       1. 第1次重试：调整点击坐标为条目“横向中间位置”（覆盖消息预览区域，x = x1 + (x2-x1)*50%）；
       2. 第2次重试：点击条目“左侧头像区域”（x = x1 + (x2-x1)*15%，确保覆盖圆形头像）；
       3. 仍失败：标记“该未读对话无法进入”，继续检查下一条，最终反馈用户。
-调用 `input_text_in_element` 向输入框填入回复内容（完成后**强制等待1.2秒**，确保与输入框水平最右侧按钮从灰色变为**蓝色可点击状态**）；  
       调用 `observe_screen` 定位发送按钮（必须同时满足：① 与输入框同水平线最右侧；② 蓝色“发送”文字；③ 距离输入框右侧边缘3-8像素），获取其 `element_id`；  
       调用 `click_element` 点击该发送按钮（点击后**停留0.8秒**，确保系统接收点击指令）；  
       必须验证：`observe_screen` 确认① 输入框内容完全清空；② 聊天记录最底部出现刚发送的消息（内容匹配），两者同时满足才判定为发送成功。  
            4. 结果反馈：回复完成后，按“平台（成功数/总未读数）”格式反馈，例：“已回复抖音（2/2）、小红书（1/2，1条未点击成功）、微信（3/3），共回复6条，1条未成功”。

            5. 特殊场景（强化重试和反馈）：
            - 遇验证码/弹窗：暂停并提示“[APP]需验证，请处理后点击‘继续回复’”。
            - 点击未读对话无效（抖音/小红书）：
                1. 第1次重试：调整点击位置至条目“正中间”（扩大点击范围）；
                2. 第2次重试：点击条目“头像区域”（确保覆盖可交互区域）；
                3. 仍失败：标记为“未回复”，继续下一条，最终反馈中注明“[APP]有X条未读因点击无效未回复，请手动处理”。

            **重要规则**：
            - 抖音/小红书必须按“定位图标→验证进入消息列表→定位未读条目→精准点击中间区域→验证进入聊天框”的顺序执行，每步验证失败均需重试或标记。
            - 禁止点击未读条目的右侧“已读按钮”“更多选项”等无关元素（通过`observe_screen`识别并避开）。
            
            
            - 所有操作基于`observe_screen`返回的实际元素，禁止依赖固定坐标（适应不同屏幕尺寸）。
        """.trimIndent() // <-- 使用 trimIndent() 来移除多余的格式化缩进
        )
        conversationHistory.add(systemMessage)
        // 添加初始的欢迎语
        conversationHistory.add(
            com.example.aiassistant.data.ChatMessage(
                role = "assistant",
                content = "你好，有什么可以帮你的吗？",
                toolCalls = null
            )
        )
        // 更新状态流，让UI显示欢迎语
        _apiMessages.value = conversationHistory.toList()
    }

    /**
     * 从UI调用的主要方法，用于发送新消息
     */
    fun sendMessage(userInput: String, context: Context) { // <--- 修改点
        // 忽略空消息
        if (userInput.isBlank()) return

        // 创建用户消息并添加到历史记录
        val userMessage = com.example.aiassistant.data.ChatMessage(role = "user", content = userInput)
        conversationHistory.add(userMessage)

        // 更新UI状态，立即显示用户发送的消息
        _apiMessages.value = conversationHistory.toList()

        // 启动一个后台协程来处理与模型的交互，避免阻塞主线程
        AgentExecutionBus.automationActive.value = true
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            processConversation(context) // <--- 修改点
        }
        activeJob?.invokeOnCompletion {
            AgentExecutionBus.automationActive.value = false
        }
    }

    fun stopCurrentWork() {
        activeJob?.cancel()
        activeJob = null
        AgentExecutionBus.automationActive.value = false
        val assistantMessage = com.example.aiassistant.data.ChatMessage(
            role = "assistant",
            content = "已停止当前自动化流程。",
            toolCalls = null
        )
        conversationHistory.add(assistantMessage)
        _apiMessages.value = conversationHistory.toList()
    }

    /**
     * 处理与大模型API的单次请求和响应
     */
    private suspend fun processConversation(context: Context) {
        val modelName = AppConfig.modelName
        val request = ChatCompletionRequest(
            model = modelName ?: "deepseek/deepseek-v3.2-251201",
            messages = conversationHistory, // 发送完整对话历史
            tools = availableTools // 告知模型它能使用哪些工具
        )

        // 调用Repository执行网络请求
        val result = repository.getCompletion(request)

        result.fold(
            onSuccess = { response ->
                // 请求成功，获取助手的回复
                val assistantMessage = response.choices.first().message
                conversationHistory.add(assistantMessage)

                // 检查助手回复是否要求调用工具
                if (assistantMessage.toolCalls.isNullOrEmpty()) {
                    // 无需调用工具，对话结束，更新UI
                    _apiMessages.value = conversationHistory.toList()
                } else {
                    // 需要调用工具，先更新UI显示模型的思考过程（可选）
                    _apiMessages.value = conversationHistory.toList()
                    // 执行工具调用，并在完成后再次调用processConversation
                    handleToolCalls(assistantMessage.toolCalls,context)
                }
            },
            onFailure = { error ->
                // 请求失败，构造一条错误消息并更新UI
                val errorMessage = com.example.aiassistant.data.ChatMessage("assistant", "抱歉，网络出错了: ${error.message}", null)
                conversationHistory.add(errorMessage)
                _apiMessages.value = conversationHistory.toList()
            }
        )
    }



    @Serializable
    data class ElementClickParams(val element_id: Int)

    @Serializable
    data class ElementInputParams(val element_id: Int, val text: String)



    /**
     * 当模型请求调用工具时执行
     */
    private suspend fun handleToolCalls(toolCalls: List<ToolCall>, context: Context) {
        // 遍历模型请求的所有工具调用
        for (toolCall in toolCalls) {
            val decision = SafetyHarness.guard(
                toolName = toolCall.function.name,
                rawArgs = toolCall.function.arguments,
                context = context
            )

            if (!decision.allowed) {
                val blockedContent = decision.reason ?: "安全拦截: 当前操作被阻止。"
                val blockedToolMessage = com.example.aiassistant.data.ChatMessage(
                    role = "tool",
                    content = blockedContent,
                    toolCallId = toolCall.id
                )
                conversationHistory.add(blockedToolMessage)
                continue
            }

            // 根据函数名调用对应的本地Kotlin函数
            val toolResultContent = when (toolCall.function.name) {
                // 已有工具
                "get_current_time" -> getCurrentTime()
                "get_current_weather" -> {
                    // 假设您有一个LocationParams数据类用于解析
                    // val params = json.decodeFromString<LocationParams>(toolCall.function.arguments)
                    // getCurrentWeather(params.location)
                    // 为了简单起见，如果您的getCurrentWeather已经处理了JSON字符串，可以直接调用
                    getCurrentWeather(toolCall.function.arguments)
                }

                // 新增的系统工具
                "simulate_click" -> {
                    val params = json.decodeFromString<ClickParams>(toolCall.function.arguments)
                    SystemTools.simulateClick(params.x, params.y)
                }
                "input_text" -> {
                    val params = json.decodeFromString<TextParams>(toolCall.function.arguments)
                    SystemTools.inputText(params.text)
                }
                "scroll_screen" -> {
                    val params = json.decodeFromString<ScrollParams>(toolCall.function.arguments)
                    val distance = if (params.distance > 0) params.distance else 800
                    val duration = if (params.duration > 0) params.duration else 300
                    SystemTools.scrollScreen(params.direction, distance, duration)
                }
                "list_available_manuals" -> {
                    listManualsFromAssets(context)
                }
                "get_manual_section" -> {
                    @Serializable data class Params(val manual_name: String, val section_query: String)
                    val params = json.decodeFromString<Params>(toolCall.function.arguments)
                    getSectionFromManual(context, params.manual_name, params.section_query)
                }
                "observe_screen" -> {
                    ScreenTools.analyzeScreen()
                }
                "click_element" -> {
                    val params = json.decodeFromString<ElementClickParams>(toolCall.function.arguments)
                    ScreenTools.clickElementById(params.element_id)
                }
                "input_text_in_element" -> {
                    val params =
                        json.decodeFromString<ElementInputParams>(toolCall.function.arguments)
                    ScreenTools.inputTextInElementById(params.element_id, params.text)
                }
                "perform_back_press" -> {
                    SystemTools.performBackPress()
                }
                "launch_app" -> {
                    try {
                        // 解析LLM传来的JSON参数
                        val params =
                            json.decodeFromString<LaunchAppParams>(toolCall.function.arguments)
                        // 调用您在第一步中创建的函数
                        val result = SystemTools.launchApp(context, params.packageName)
                        // 检查操作是否成功
                        if (result.startsWith("应用 ")) {
                            // 操作成功，检查是否需要创建说明书
                            checkAndCreateManual(context, params.packageName)
                            // 更新流程总结
                            updateManualProcess(context, params.packageName)
                        }
                        result
                    } catch (e: Exception) {
                        "错误：解析参数失败 - ${e.message}"
                    }
                }
                "get_installed_apps" -> {
                    // 这个工具不需要参数，直接调用即可
                    SystemTools.getInstalledApps(context)
                }
                "return_to_home_screen" -> {
                    // 调用在第一步中创建的函数
                    SystemTools.returnToHomeScreen(context)
                }
                    else -> "错误：未知的工具 ${toolCall.function.name}"
            }

            // 将工具的执行结果封装成一条 "tool" 角色的消息
            val toolMessage = com.example.aiassistant.data.ChatMessage(
                role = "tool",
                content = toolResultContent,
                toolCallId = toolCall.id // 必须包含tool_call_id以对应请求
            )
            conversationHistory.add(toolMessage)
        }

        // 更新UI以显示工具消息（可选，通常不显示）
        _apiMessages.value = conversationHistory.toList()

        // 在所有工具都执行完毕后，再次调用模型，让模型根据所有工具结果生成最终回复
        processConversation(context)
    }


    /**
     * 解析Markdown文件，将其内容按二级标题 (##) 分割成章节Map.
     * @param markdownContent 完整的md文件内容.
     * @return 一个Map，Key是章节标题，Value是该章节的完整内容.
     */
    fun parseMarkdownManual(markdownContent: String): Map<String, String> {
        val sections = mutableMapOf<String, String>()
        val lines = markdownContent.lines()
        var currentSectionTitle: String? = null
        val currentSectionContent = StringBuilder()

        for (line in lines) {
            if (line.startsWith("## ")) {
                if (currentSectionTitle != null) {
                    sections[currentSectionTitle] = currentSectionContent.toString().trim()
                }
                currentSectionTitle = line.substring(3).trim()
                currentSectionContent.clear()
            } else {
                currentSectionContent.append(line).append("\n")
            }
        }

        if (currentSectionTitle != null) {
            sections[currentSectionTitle] = currentSectionContent.toString().trim()
        }

        return sections
    }



    private fun listManualsFromAssets(context: Context): String {
        return try {
            // 先从内部存储读取
            val internalManualsDir = File(context.filesDir, "manuals")
            val internalManuals = if (internalManualsDir.exists()) {
                internalManualsDir.listFiles { _, name -> name.endsWith(".md") }?.map { it.name } ?: emptyList()
            } else {
                emptyList()
            }
            
            // 再从assets读取
            val assetsManuals = context.assets.list("manuals")?.toList() ?: emptyList()
            
            // 合并去重
            val allManuals = (internalManuals + assetsManuals).distinct()
            Json.encodeToString(ListSerializer(String.serializer()), allManuals)
        } catch (e: Exception) {
            "错误: 无法读取说明书列表，原因：${e.message}"
        }
    }

    private fun getSectionFromManual(context: Context, manualName: String, query: String): String {
        return try {
            // 1. 先尝试从内部存储读取
            val internalManualFile = File(File(context.filesDir, "manuals"), manualName)
            val markdownContent = if (internalManualFile.exists()) {
                internalManualFile.readText(StandardCharsets.UTF_8)
            } else {
                // 2. 如果内部存储没有，从assets读取
                context.assets.open("manuals/$manualName").bufferedReader().use { it.readText() }
            }
            
            val sections = parseMarkdownManual(markdownContent)

            // 3. 查找最相关的章节 (这里使用简单的关键词匹配)
            val bestMatch = sections.entries.find { (title, _) ->
                title.contains(query, ignoreCase = true)
            }

            if (bestMatch != null) {
                // 找到了，返回章节内容
                "从 '$manualName' 中找到相关章节:\n\n${bestMatch.value}"
            } else {
                // 没找到
                val availableTitles = if (sections.keys.isNotEmpty()) {
                    sections.keys.joinToString()
                } else {
                    "无可用章节标题"
                }
                "在 '$manualName' 中未找到关于 '$query' 的章节。可用的章节标题有: $availableTitles"
            }
        } catch (e: Exception) {
            "错误: 无法读取或解析说明书 '$manualName'，原因: ${e.message}"
        }
    }

    // 当前正在操作的应用包名
    private var currentAppPackageName: String? = null
    
    /**
     * 检查并创建应用说明书
     * @param context 上下文环境
     * @param packageName 应用包名
     */
    private fun checkAndCreateManual(context: Context, packageName: String) {
        try {
            // 使用内部存储来存储说明书
            val manualsDir = File(context.filesDir, "manuals")
            if (!manualsDir.exists()) {
                manualsDir.mkdirs()
            }
            
            // 获取应用名称
            val appName = try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
            
            // 设置当前正在操作的应用包名
            currentAppPackageName = packageName
            
            // 使用应用中文名称作为文件名（替换空格为下划线）
            val safeAppName = appName.replace(" ", "_")
            val manualFileName = "${safeAppName}_manual.md"
            val manualFile = File(manualsDir, manualFileName)
            
            if (!manualFile.exists()) {
                // 如果说明书不存在，创建新的
                // 创建说明书内容，使用与assets一致的格式
                val manualContent = generateManualContent(appName, packageName, "")
                
                // 写入到内部存储
                manualFile.writeText(manualContent, StandardCharsets.UTF_8)
                Log.d("ChatViewModel", "创建新说明书: ${manualFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "检查或创建说明书失败", e)
        }
    }
    
    /**
     * 更新应用说明书的流程总结
     * @param context 上下文环境
     * @param packageName 应用包名
     */
    private fun updateManualProcess(context: Context, packageName: String) {
        try {
            val manualsDir = File(context.filesDir, "manuals")
            
            // 获取应用名称
            val appName = try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
            
            // 使用应用中文名称作为文件名（替换空格为下划线）
            val safeAppName = appName.replace(" ", "_")
            val manualFileName = "${safeAppName}_manual.md"
            val manualFile = File(manualsDir, manualFileName)
            
            if (manualFile.exists()) {
                
                // 读取现有内容
                var existingContent = manualFile.readText(StandardCharsets.UTF_8)
                
                // 根据应用包名生成新的功能章节
                val newSections = generateNewFunctionSections(packageName, appName, existingContent)
                
                // 如果有新的章节，添加到说明书中
                if (newSections.isNotEmpty()) {
                    // 查找最后一个编号章节的编号
                    val lastNumber = getLastSectionNumber(existingContent)
                    var nextNumber = lastNumber + 1
                    
                    // 添加新章节
                    var sectionsToAdd = ""
                    for (section in newSections) {
                        sectionsToAdd += """
## ${nextNumber}. ${section.title}
${section.content}
                        """.trimIndent() + "\n\n"
                        nextNumber++
                    }
                    
                    // 添加到现有内容的末尾
                    existingContent += sectionsToAdd
                    
                    // 写入更新后的内容
                    manualFile.writeText(existingContent, StandardCharsets.UTF_8)
                    Log.d("ChatViewModel", "更新说明书，添加新功能章节: ${manualFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "更新流程总结失败", e)
        }
    }
    
    /**
     * 生成新的功能章节
     * @param packageName 应用包名
     * @param appName 应用名称
     * @param existingContent 现有内容
     * @return 新的功能章节列表
     */
    private fun generateNewFunctionSections(packageName: String, appName: String, existingContent: String): List<FunctionSection> {
        val sections = mutableListOf<FunctionSection>()
        
        when (packageName) {
            "com.bilibili.app.in" -> {
                // B站功能章节
                val searchSection = FunctionSection(
                    title = "搜索并播放视频/电影",
                    content = """1. 打开 **$appName**，如有广告弹窗点击 **跳过**。
2. 点击顶部 **搜索栏**（放大镜图标）。
3. 输入搜索内容（视频名称或电影名称）。
4. 在搜索结果中找到目标内容并点击。
5. 点击播放按钮开始观看。"""
                )
                
                val browseSection = FunctionSection(
                    title = "浏览并播放视频",
                    content = """1. 打开 **$appName**，如有广告弹窗点击 **跳过**。
2. 浏览首页推荐视频。
3. 点击感兴趣的视频开始观看。"""
                )
                
                // 检查是否已存在这些章节
                if (!existingContent.contains(searchSection.title)) {
                    sections.add(searchSection)
                }
                if (!existingContent.contains(browseSection.title)) {
                    sections.add(browseSection)
                }
            }
            "com.tencent.mm" -> {
                // 微信功能章节
                val sendMessageSection = FunctionSection(
                    title = "发送消息",
                    content = """1. 打开 **$appName**，如有广告弹窗点击 **跳过**。
2. 选择要发送消息的联系人或群聊。
3. 点击输入框。
4. 输入消息内容。
5. 点击发送按钮。"""
                )
                
                if (!existingContent.contains(sendMessageSection.title)) {
                    sections.add(sendMessageSection)
                }
            }
            else -> {
                // 通用应用功能章节
                val basicSection = FunctionSection(
                    title = "基本操作流程",
                    content = """1. 打开 **$appName**，如有广告弹窗点击 **跳过**。
2. 根据界面提示进行操作。
3. 点击相应的功能按钮。
4. 查看操作结果。"""
                )
                
                if (!existingContent.contains(basicSection.title)) {
                    sections.add(basicSection)
                }
            }
        }
        
        return sections
    }
    
    /**
     * 获取现有内容中最后一个编号章节的编号
     * @param content 现有内容
     * @return 最后一个编号章节的编号
     */
    private fun getLastSectionNumber(content: String): Int {
        val pattern = Regex("## (\\d+)\\.")
        val matches = pattern.findAll(content)
        val numbers = matches.map { it.groupValues[1].toInt() }.toList()
        return numbers.maxOrNull() ?: 1
    }
    
    /**
     * 功能章节数据类
     */
    data class FunctionSection(
        val title: String,
        val content: String
    )
    
    /**
     * 生成与assets格式一致的说明书内容
     * @param appName 应用名称
     * @param packageName 应用包名
     * @param appSpecificContent 应用特定的功能操作说明
     * @return 说明书内容
     */
    private fun generateManualContent(appName: String, packageName: String, appSpecificContent: String): String {
        return """
# $appName APP 指南

## 1. 启动应用打开方法
1. 打开 **$appName**，如有广告弹窗点击 **跳过**。
2. 等待应用完全加载
3. 进入应用主界面
        """.trimIndent()
    }

}