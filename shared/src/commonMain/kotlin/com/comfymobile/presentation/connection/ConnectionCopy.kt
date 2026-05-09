package com.comfymobile.presentation.connection

enum class ConnectionLanguage {
    Zh,
    En,
}

data class LocalizedText(
    val zh: String,
    val en: String,
) {
    fun resolve(language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> zh
        ConnectionLanguage.En -> en
    }
}

object ConnectionCopy {
    val firstRunHeader = LocalizedText(
        zh = "连接你的 ComfyUI 服务器",
        en = "Connect to your ComfyUI server",
    )
    val ipHelperBody = LocalizedText(
        zh = "在电脑上启动 ComfyUI，命令行会打印 Listening at http://192.168.x.x:8188。把这个地址输入下面。",
        en = "Start ComfyUI on your computer; it prints Listening at http://192.168.x.x:8188 in the terminal. Enter that here.",
    )
    val ipHelperAlt = LocalizedText(
        zh = "示意：ComfyUI 启动日志中的 IP 地址",
        en = "Example: ComfyUI startup log showing the IP",
    )
    val noSavedServers = LocalizedText(
        zh = "还没有已保存的服务器",
        en = "No saved servers yet",
    )
    val mdnsPlaceholder = LocalizedText(
        zh = "自动发现服务器（未来版本）",
        en = "Auto-discover servers (future version)",
    )
    val friendlyNameTitle = LocalizedText(
        zh = "给这个服务器起个名字",
        en = "Name this server",
    )
    val friendlyNameBody = LocalizedText(
        zh = "方便你下次一眼认出（可选）。",
        en = "So you can recognize it next time (optional).",
    )
    val retry = LocalizedText(zh = "重试", en = "Retry")
    val connect = LocalizedText(zh = "连接", en = "Connect")
    val save = LocalizedText(zh = "保存", en = "Save")
    val cancel = LocalizedText(zh = "取消", en = "Cancel")
    val rename = LocalizedText(zh = "重命名", en = "Rename")
    val delete = LocalizedText(zh = "删除", en = "Delete")
    val hostLabel = LocalizedText(zh = "服务器地址", en = "Server address")
    val portLabel = LocalizedText(zh = "端口", en = "Port")
    val friendlyNameLabel = LocalizedText(zh = "友好名字（可选）", en = "Friendly name (optional)")
    val historyTitle = LocalizedText(zh = "已保存服务器", en = "Saved servers")
    val technicalDetails = LocalizedText(zh = "技术细节", en = "Technical details")
    val connected = LocalizedText(zh = "已连接", en = "Connected")
    val lanFlake = LocalizedText(zh = "网络小波动，正在重连…", en = "Brief network blip, reconnecting…")
    val backgroundResumed = LocalizedText(zh = "欢迎回来，正在检查你的生成…", en = "Welcome back, checking your generation…")
    val lost = LocalizedText(zh = "连接已断开", en = "Connection lost")
    val waitingReconnectToCancel = LocalizedText(zh = "等待重连后可取消", en = "Can cancel after reconnecting")
}
