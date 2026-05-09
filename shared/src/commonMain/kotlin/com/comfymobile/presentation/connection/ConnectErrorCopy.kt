package com.comfymobile.presentation.connection

import com.comfymobile.data.network.ConnectError

/**
 * UI-facing translation of [ConnectError]. The data layer holds
 * technical categories only ([ConnectError]); this object owns the
 * user-visible strings (per the layer split agreed with @Lily on
 * #ComfyMobile:af119226 msg `1fa6de6f`).
 *
 * Bilingual zh/en pairs are returned so the connection screen can
 * pick the active locale at render time. Future locales live here,
 * not anywhere closer to the network stack.
 */
object ConnectErrorCopy {

    /** Headline + suggestion shown on the Lost / failed-connect sheet. */
    data class Lookup(
        val titleZh: String,
        val titleEn: String,
        val bodyZh: String,
        val bodyEn: String,
    )

    fun lookup(error: ConnectError): Lookup = when (error) {
        ConnectError.FORMAT -> Lookup(
            titleZh = "地址格式不对",
            titleEn = "Invalid address",
            bodyZh = "请检查 IP 和端口的格式，例如 192.168.1.10:8188。",
            bodyEn = "Check the host:port format, e.g. 192.168.1.10:8188.",
        )
        ConnectError.TIMEOUT -> Lookup(
            titleZh = "连接超时",
            titleEn = "Connection timed out",
            bodyZh = "服务器无回应。请确认 ComfyUI 已启动，并且手机和电脑在同一 Wi-Fi。",
            bodyEn = "No response from the server. Make sure ComfyUI is running and your phone is on the same Wi-Fi.",
        )
        ConnectError.REFUSED -> Lookup(
            titleZh = "连接被拒",
            titleEn = "Connection refused",
            bodyZh = "端口没有响应。请确认 ComfyUI 已启动，并检查防火墙是否拦截。",
            bodyEn = "The port is closed. Make sure ComfyUI is running and not blocked by a firewall.",
        )
        ConnectError.TLS_HANDSHAKE -> Lookup(
            titleZh = "无法建立安全连接",
            titleEn = "Couldn't establish a secure connection",
            bodyZh = "ComfyUI 在局域网通常不需要 https。请去掉 https://，使用 http://。",
            bodyEn = "ComfyUI on a local network usually doesn't need https. Try without the https:// prefix.",
        )
        ConnectError.NOT_COMFYUI -> Lookup(
            titleZh = "联通了一个服务，但不像是 ComfyUI",
            titleEn = "Reached a server, but it doesn't look like ComfyUI",
            bodyZh = "请检查端口是否对，ComfyUI 默认是 8188。",
            bodyEn = "Check the port — ComfyUI defaults to 8188.",
        )
        ConnectError.WRONG_PORT_404 -> Lookup(
            titleZh = "ComfyUI 在那里，但路径不对",
            titleEn = "ComfyUI is there, but the path is wrong",
            bodyZh = "服务器返回了 404。请确认 ComfyUI 启动时打印的地址。",
            bodyEn = "The server returned 404. Verify the address that ComfyUI printed at startup.",
        )
        ConnectError.UNKNOWN -> Lookup(
            titleZh = "连接失败",
            titleEn = "Couldn't connect",
            bodyZh = "未知原因。请重试，或者重启 ComfyUI 后再试。",
            bodyEn = "Something went wrong. Try again, or restart ComfyUI and retry.",
        )
    }
}
