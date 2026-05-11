package com.comfymobile.data.importer

import platform.Foundation.NSURL

private val pendingUrls = ArrayDeque<NSURL>()

@Suppress("unused")
fun enqueueIosWorkflowImportUrl(url: NSURL) {
    pendingUrls.addLast(url)
}

internal fun consumeIosWorkflowImportUrl(): NSURL? =
    if (pendingUrls.isEmpty()) null else pendingUrls.removeFirst()
