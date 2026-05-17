package com.comfymobile.presentation.run

import com.comfymobile.presentation.connection.ConnectionCopy
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.LocalizedText

/**
 * Localized strings for [RunScreen]. Mirrors the Phase 2 convention
 * established by [ConnectionCopy] / `OutputGalleryCopy` /
 * `HistoryCopy` / `ConnectErrorCopy`: all user-facing copy lives in
 * a single `object` of [LocalizedText] entries, resolved by the
 * surface via [LocalizedText.resolve] against the user's
 * [ConnectionLanguage].
 *
 * Banner texts are deliberately re-exposed via [ConnectionCopy.lanFlake]
 * / `.backgroundResumed` / `.lost` rather than duplicated here, so a
 * future copy update in ConnectionCopy doesn't drift between the
 * connect screen and the run screen.
 *
 * Per @Ores PR #31 review (msg `10846076`).
 */
object RunCopy {

    // ----------------------------------------------------------------- top status row

    val phaseIdle = LocalizedText(zh = "尚未运行", en = "Not running yet")
    val phaseSubmitting = LocalizedText(zh = "提交中…", en = "Submitting…")
    val phaseQueued = LocalizedText(zh = "排队中", en = "Queued")
    val phaseRunning = LocalizedText(zh = "运行中", en = "Running")
    val phaseSucceeded = LocalizedText(zh = "已完成", en = "Completed")
    val phaseFailed = LocalizedText(zh = "生成失败", en = "Generation failed")
    val phaseCancelled = LocalizedText(zh = "已取消", en = "Cancelled")

    val cancel = LocalizedText(zh = "取消", en = "Cancel")
    val close = LocalizedText(zh = "关闭", en = "Close")

    val run = LocalizedText(zh = "运行", en = "Run")
    val tryAgain = LocalizedText(zh = "再试一次", en = "Try again")
    val rerun = LocalizedText(zh = "再次运行", en = "Run again")

    // ----------------------------------------------------------------- sub-labels

    /** Queue position template, e.g. "队列位置 #3" / "Queue position #3". */
    fun queuePositionLabel(position: Int, language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "队列位置 #$position"
        ConnectionLanguage.En -> "Queue position #$position"
    }

    /** Current-node template with displayName, e.g. "当前节点：KSampler" / "Current node: KSampler". */
    fun currentNodeLabel(displayName: String, language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "当前节点：$displayName"
        ConnectionLanguage.En -> "Current node: $displayName"
    }

    /** Current-node template by id only. */
    fun currentNodeIdLabel(nodeId: String, language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "当前节点 #$nodeId"
        ConnectionLanguage.En -> "Current node #$nodeId"
    }

    /** Output count summary. */
    fun outputCountLabel(count: Int, language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "${count} 张图已生成"
        ConnectionLanguage.En -> if (count == 1) "1 image generated" else "$count images generated"
    }

    /** Progress detail card heading: current node displayName or "Processing" fallback. */
    val processing = LocalizedText(zh = "处理中", en = "Processing")

    /** Detail-card "最近产出：filename" line. */
    fun lastOutputLine(filename: String, language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "最近产出：$filename"
        ConnectionLanguage.En -> "Last output: $filename"
    }

    // ----------------------------------------------------------------- cancel confirm sheet

    val cancelConfirmTitle = LocalizedText(zh = "确定取消？", en = "Cancel this run?")
    val cancelConfirmBody = LocalizedText(zh = "已生成的部分将丢失。", en = "Any partial progress will be lost.")
    val cancelConfirmConfirm = LocalizedText(zh = "取消生成", en = "Cancel run")
    val cancelConfirmDismiss = LocalizedText(zh = "继续运行", en = "Keep running")

    // ----------------------------------------------------------------- terminal sheets

    val terminalCancelledTitle = LocalizedText(zh = "已取消", en = "Cancelled")
    val terminalCancelledBody = LocalizedText(zh = "本次生成已取消。", en = "This run was cancelled.")

    /** Failing-node label prefix: "失败节点：" / "Failing node: ". */
    fun failingNodeLabel(displayName: String, language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "失败节点：$displayName"
        ConnectionLanguage.En -> "Failing node: $displayName"
    }

    // ----------------------------------------------------------------- RunError → display

    val errorTitleValidation = LocalizedText(zh = "工作流无法提交", en = "Workflow rejected")
    val errorTitleNodeException = LocalizedText(zh = "生成失败", en = "Generation failed")
    val errorTitleNetwork = LocalizedText(zh = "无法到达服务端", en = "Cannot reach the server")
    val errorTitleNoOutputs = LocalizedText(zh = "未生成任何产物", en = "No outputs generated")

    fun errorMessageValidation(failingNodeIds: String, language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "服务端拒绝了部分节点：$failingNodeIds"
        ConnectionLanguage.En -> "The server rejected these nodes: $failingNodeIds"
    }

    fun errorMessageNoOutputs(language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "工作流执行成功但没有图片输出（缺少 SaveImage / PreviewImage 节点？）"
        ConnectionLanguage.En -> "Workflow finished but produced no image (missing a SaveImage / PreviewImage node?)"
    }

    fun errorMessageNetworkFallback(language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "网络错误"
        ConnectionLanguage.En -> "Network error"
    }

    // ----------------------------------------------------------------- banners (reuse ConnectionCopy)

    /**
     * The three connection-branch banner texts are shared with the
     * connect screen so a future copy update lands in one place.
     * Re-exposed here as references to keep call sites short.
     */
    val bannerLanFlake: LocalizedText = ConnectionCopy.lanFlake
    val bannerBackgroundResumed: LocalizedText = ConnectionCopy.backgroundResumed
    val bannerOffline: LocalizedText = LocalizedText(
        zh = "已离线，请检查 Wi-Fi 后重试",
        en = "Offline. Check Wi-Fi and retry.",
    )
}
