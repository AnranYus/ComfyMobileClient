package com.comfymobile.presentation.workflow

import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.LocalizedText

/**
 * Localized strings for [WorkflowGraphRoute] (Phase 2 close-out per
 * @Ores T2.7 §1.10). Mirrors the convention from `RunCopy` /
 * `WorkflowLibraryCopy`: one `object` of [LocalizedText] entries the
 * surface resolves against the user's [ConnectionLanguage].
 *
 * Most user-facing copy on this surface comes from re-used Composables
 * (top-bar back arrow / Run FAB / ParamEditor drawer), so this file
 * mainly hosts the route-specific labels: loading / not-found / API
 * format unsupported / first-frame hint.
 */
object WorkflowGraphCopy {

    /** Top app bar back-arrow content description. */
    val back = LocalizedText(zh = "返回", en = "Back")

    /** Run FAB label (consistent with [com.comfymobile.presentation.run.RunCopy.run]). */
    val run = LocalizedText(zh = "运行", en = "Run")

    /** Shown while [com.comfymobile.domain.workflow.WorkflowRepository.getById] is in flight. */
    val loading = LocalizedText(zh = "正在加载工作流…", en = "Loading workflow…")

    /** Shown when the workflowId doesn't resolve to a row. */
    val workflowNotFound = LocalizedText(
        zh = "未找到该工作流。它可能已被删除。",
        en = "Workflow not found. It may have been deleted.",
    )

    /**
     * Shown when the loaded envelope is API-format. The mobile graph
     * renderer needs UI-format `nodes[]` + `links[]` + positions —
     * API format only has the flat `{node_id: {class_type, inputs}}`
     * structure with no positions, so we can't render the canvas.
     */
    val apiFormatUnsupported = LocalizedText(
        zh = "此工作流是 API 格式，无法在移动端显示图形。可以直接运行，但不能可视化编辑。",
        en = "This workflow is in API format and can't be displayed graphically on mobile. " +
            "You can run it directly, but the canvas view is unavailable.",
    )

    /** First-frame onboarding tooltip per @Ores T2.7 §1.9 (one-shot per session). */
    val firstFrameHint = LocalizedText(
        zh = "长按节点编辑参数 · 双指缩放 / 拖动浏览",
        en = "Long-press a node to edit · pinch to zoom · drag to pan",
    )

    /** Dismiss-tooltip label. */
    val gotIt = LocalizedText(zh = "我知道了", en = "Got it")
}
