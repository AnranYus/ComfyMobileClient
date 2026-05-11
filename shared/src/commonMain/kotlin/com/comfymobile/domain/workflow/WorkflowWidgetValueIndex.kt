package com.comfymobile.domain.workflow

import com.comfymobile.domain.node.NodeDescriptor
import com.comfymobile.domain.node.ParamDescriptor

/**
 * Resolves descriptor parameters to ComfyUI editor-save
 * `widgets_values[]` slots.
 *
 * Most nodes are straightforward: descriptor editable param N maps
 * to widget slot N. A few ComfyUI nodes inject UI-only widgets into
 * `widgets_values` though, notably `KSampler.control_after_generate`
 * immediately after `seed`. The API converter already owns the
 * canonical per-class widget order; this helper reuses it so graph
 * summaries and the param drawer read/write the same slot the submit
 * path later converts to API inputs.
 */
object WorkflowWidgetValueIndex {

    fun indexOf(
        classType: String,
        descriptor: NodeDescriptor,
        param: ParamDescriptor,
    ): Int? {
        val whitelistOrder = WorkflowConverter.WHITELIST_WIDGET_ORDER[classType]
        val fromWhitelist = whitelistOrder?.indexOf(param.name)?.takeIf { it >= 0 }
        if (fromWhitelist != null) return fromWhitelist

        return descriptor.editableParams.indexOfFirst { it.name == param.name }
            .takeIf { it >= 0 }
    }
}
