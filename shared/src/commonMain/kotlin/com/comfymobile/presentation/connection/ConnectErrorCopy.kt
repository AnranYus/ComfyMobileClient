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

    /**
     * What the primary CTA button on the error sheet does. Per @Lily
     * PR #19 review comment `4413981846` blocker 2: a CTA labelled
     * "Choose / 去选择" must NOT dispatch [com.comfymobile.data.network.ConnectionInput.Retry]
     * — retrying without a server immediately returns to
     * `Lost(NO_ACTIVE_SERVER)` and the user is stuck in a retry loop.
     * The `DISMISS` variant routes the button click to
     * [ConnectActions.onDismissError] which closes the error sheet
     * and reveals the connect form / history list underneath.
     */
    enum class PrimaryAction { RETRY, DISMISS }

    /** Headline + suggestion shown on the Lost / failed-connect sheet. */
    data class Lookup(
        val titleZh: String,
        val titleEn: String,
        val bodyZh: String,
        val bodyEn: String,
        val suggestionZh: String? = null,
        val suggestionEn: String? = null,
        val primaryCtaZh: String = "重试",
        val primaryCtaEn: String = "Retry",
        val primaryAction: PrimaryAction = PrimaryAction.RETRY,
    )

    /**
     * Resolve which click handler the primary CTA button should fire
     * for an error with the given [primaryAction]. Extracted as a
     * pure function so unit tests can lock the routing contract
     * without spinning up a Compose UI test harness — see
     * `ConnectErrorCopyTest.choose_button_for_no_active_server_routes_to_dismiss_not_retry`.
     *
     * Per @Lily PR #19 review `4413981846` blocker 2: a CTA labelled
     * "Choose / 去选择" must route to dismiss; otherwise the user
     * loops back into `Lost(NO_ACTIVE_SERVER)` immediately.
     */
    fun primaryClickHandler(
        primaryAction: PrimaryAction,
        onRetry: () -> Unit,
        onDismiss: () -> Unit,
    ): () -> Unit = when (primaryAction) {
        PrimaryAction.RETRY -> onRetry
        PrimaryAction.DISMISS -> onDismiss
    }

    fun lookup(error: ConnectError): Lookup = when (error) {
        ConnectError.FORMAT -> Lookup(
            titleZh = "地址格式不对",
            titleEn = "Invalid address",
            bodyZh = "检查 IP 和端口的格式（示例：192.168.1.5:8188）。",
            bodyEn = "Check IP and port format (example: 192.168.1.5:8188).",
            suggestionZh = "修正后再试",
            suggestionEn = "Fix and retry",
        )
        ConnectError.TIMEOUT -> Lookup(
            titleZh = "服务器没响应",
            titleEn = "Server didn't respond",
            bodyZh = "连接超时。请确认 ComfyUI 已启动，并且手机和电脑在同一个 Wi-Fi 下。",
            bodyEn = "Connection timed out. Make sure ComfyUI is running and your phone is on the same Wi-Fi as the computer.",
            suggestionZh = "检查 Wi-Fi 是否一致",
            suggestionEn = "Check Wi-Fi parity",
        )
        ConnectError.REFUSED -> Lookup(
            titleZh = "连接被拒绝",
            titleEn = "Connection refused",
            bodyZh = "服务器在线但拒绝了连接。可能 ComfyUI 没启动，或者端口被防火墙挡了。",
            bodyEn = "The server is online but rejected the connection. ComfyUI may not be running, or the port is blocked.",
            suggestionZh = "检查 ComfyUI 是否启动 / 检查防火墙",
            suggestionEn = "Check that ComfyUI is running / check the firewall",
        )
        ConnectError.TLS_HANDSHAKE -> Lookup(
            titleZh = "加密握手失败",
            titleEn = "TLS handshake failed",
            bodyZh = "无法建立安全连接。试试不带 https，因为 LAN 模式默认是明文。",
            bodyEn = "Couldn't establish a secure connection. Try without https — LAN mode uses plaintext by default.",
            suggestionZh = "去掉 https://",
            suggestionEn = "Remove https://",
        )
        ConnectError.NOT_COMFYUI -> Lookup(
            titleZh = "这不像 ComfyUI",
            titleEn = "Doesn't look like ComfyUI",
            bodyZh = "这个地址有响应，但不是 ComfyUI。请确认你输入了 ComfyUI 服务器的 IP，而不是别的服务。",
            bodyEn = "Something is responding at that address, but it's not ComfyUI. Make sure you entered the ComfyUI server's IP, not another service.",
            suggestionZh = "换一个地址",
            suggestionEn = "Try a different address",
        )
        ConnectError.WRONG_PORT_404 -> Lookup(
            titleZh = "ComfyUI 不在这个端口",
            titleEn = "ComfyUI isn't at this port",
            bodyZh = "服务器在线，但这个端口上没找到 ComfyUI（/system_stats 返回 404）。换一个端口看看。",
            bodyEn = "The server is reachable, but ComfyUI isn't at this port (/system_stats returned 404). Try a different port.",
            suggestionZh = "换个端口（默认 8188）",
            suggestionEn = "Try another port (default 8188)",
        )
        // Final copy from @Ores `b522a9f3` (PR #18 thread). CTA is
        // intentionally NOT "Retry" — retrying without a server does
        // nothing; pushing the user back to the connect form is the
        // correct affordance, so [primaryAction] is [PrimaryAction.DISMISS]
        // (per @Lily PR #19 review `4413981846` blocker 2).
        ConnectError.NO_ACTIVE_SERVER -> Lookup(
            titleZh = "没有选中的服务器",
            titleEn = "No active server",
            bodyZh = "还没有选好要连的服务器。从历史里挑一个，或在表单里输入 IP。",
            bodyEn = "No server is selected. Pick one from history, or enter an IP again.",
            suggestionZh = "选服务器或重新输入",
            suggestionEn = "Pick a server or enter again",
            primaryCtaZh = "去选择",
            primaryCtaEn = "Choose",
            primaryAction = PrimaryAction.DISMISS,
        )
        ConnectError.UNKNOWN -> Lookup(
            titleZh = "连接失败",
            titleEn = "Connection failed",
            bodyZh = "不太确定问题出在哪儿。展开下面的“技术细节”看看原始信息。",
            bodyEn = "Not sure what went wrong. Expand \"Technical details\" below for the raw message.",
        )
    }
}
