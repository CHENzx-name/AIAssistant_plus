# AI Assistant - Android自动化助手

一款智能的Android应用自动化操作助手，集成大语言模型，能够自动执行手机操作并生成操作说明书。

## ✨ 功能特性

### 🤖 智能交互
- **大模型对话**：集成兼容OpenAI接口的大语言模型，支持自然语言对话
- **语音输入**：支持语音输入指令
- **实时响应**：快速处理用户请求并执行操作

### 📱 自动化操作
- **应用启动**：自动打开指定应用
- **界面交互**：点击、滑动、输入文本等操作
- **屏幕观察**：实时分析屏幕内容
- **多应用支持**：支持各种常用应用的操作

### 📚 智能说明书
- **自动生成**：根据操作自动生成应用操作说明书
- **流程总结**：智能总结操作流程，生成结构化的步骤说明
- **分类管理**：区分默认说明书和用户创建的说明书
- **编辑功能**：支持编辑用户创建的说明书

### ⚙️ 配置管理
- **API配置**：支持配置API Key和模型名称
- **持久化存储**：配置自动保存到SharedPreferences
- **灵活设置**：支持不同模型和接口的切换

## 🛠️ 技术栈

- **开发语言**：Kotlin
- **网络框架**：Retrofit + OkHttp
- **序列化**：Kotlinx Serialization
- **UI组件**：AndroidX
- **服务**：前台服务、无障碍服务
- **存储**：内部存储、SharedPreferences

## 🚀 快速开始

### 环境要求
- Android Studio
- minSdk: 26
- targetSdk: 35
- compileSdk: 35
- JDK: 11

### 安装步骤
1. 使用Android Studio打开项目
2. 同步Gradle依赖
3. 连接Android设备或启动模拟器
4. 运行app模块

### 使用方法
1. **启动应用**：打开AI Assistant应用
2. **配置API**：进入设置页面，配置API Key和模型名称
3. **启用权限**：根据提示启用无障碍服务和前台服务
4. **开始对话**：在聊天界面输入指令，如"打开B站并播放电影"
5. **查看说明书**：在设置页面点击"查看说明书"查看生成的操作指南

## 📖 使用示例

### 基本操作
```
打开微信
```

### 复杂操作
```
打开B站并搜索电影
```

### 多步骤操作
```
打开微信，给张三发送消息"你好"
```

## 🔧 API配置

应用默认使用兼容OpenAI的`/chat/completions`接口。

### 配置位置
- **API Key**：在设置页面配置
- **模型名称**：在设置页面配置
- **配置文件**：`app/src/main/java/com/example/aiassistant/config/AppConfig.kt`

### 接口定义
- **Retrofit配置**：`app/src/main/java/com/example/aiassistant/data/RetrofitClient.kt`
- **API服务**：`app/src/main/java/com/example/aiassistant/data/ChatRepository.kt`

## 📋 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 访问大模型API接口 |
| FOREGROUND_SERVICE | 前台服务支持 |
| POST_NOTIFICATIONS | 通知权限 |
| BIND_ACCESSIBILITY_SERVICE | 无障碍服务，用于自动化操作 |

## 📁 项目结构

```
app/src/main/
├── java/com/example/aiassistant/
│   ├── ui/                  # 界面相关
│   │   ├── MainActivity.kt   # 主界面
│   │   ├── SettingsActivity.kt # 设置界面
│   │   ├── ManualsActivity.kt # 说明书列表
│   │   └── ManualDetailActivity.kt # 说明书详情
│   ├── data/                # 数据相关
│   │   ├── RetrofitClient.kt # Retrofit配置
│   │   ├── ChatRepository.kt # 网络请求仓库
│   │   └── ChatModels.kt    # 数据模型
│   ├── services/            # 服务相关
│   │   ├── ForegroundService.kt # 前台服务
│   │   └── AccessibilityService.kt # 无障碍服务
│   ├── config/              # 配置相关
│   │   └── AppConfig.kt     # 全局配置
│   └── tools/               # 工具类
│       ├── SystemTools.kt   # 系统工具
│       └── ScreenTools.kt   # 屏幕工具
├── res/                     # 资源文件
│   ├── layout/              # 布局文件
│   ├── menu/                # 菜单文件
│   └── xml/                 # XML配置
└── assets/                  # 静态资源
    └── manuals/             # 默认说明书
```

## ❓ 常见问题

### 无障碍服务未启用
应用会在启动后弹窗引导开启，请按照提示操作。

### API调用失败
- 检查API Key是否正确
- 检查网络连接
- 确认模型名称正确

### 说明书显示问题
- 确保内部存储权限正常
- 检查文件路径是否正确

### 应用闪退
- 检查日志获取错误信息
- 确保设备系统版本符合要求
- 清理应用缓存后重试

## 🤝 贡献指南

欢迎提交Issue和Pull Request！

## 📄 许可证

Apache License 2.0

```
Copyright (c) 2026 AI Assistant Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
