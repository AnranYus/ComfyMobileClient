package com.comfymobile.presentation.connection

data class ServerFormState(
    val host: String = "",
    val port: String = DEFAULT_PORT.toString(),
    val friendlyName: String = "",
    val isSubmitting: Boolean = false,
    val showValidationErrors: Boolean = false,
) {
    companion object {
        const val DEFAULT_PORT: Int = 8188
    }
}

data class ServerFormValidation(
    val hostError: LocalizedText? = null,
    val portError: LocalizedText? = null,
    val friendlyNameError: LocalizedText? = null,
    val duplicateNameWarning: LocalizedText? = null,
) {
    val canSubmit: Boolean
        get() = hostError == null && portError == null && friendlyNameError == null
}

data class ServerFormSubmit(
    val host: String,
    val port: Int,
    val friendlyName: String?,
) {
    val serverId: String
        get() = "$host:$port"
}

object ServerFormValidator {
    const val INLINE_ERROR_DEBOUNCE_MS: Long = 300L

    fun validate(
        form: ServerFormState,
        existingFriendlyNames: Collection<String> = emptyList(),
    ): ServerFormValidation {
        val trimmedHost = form.host.trim()
        val trimmedPort = form.port.trim()
        val trimmedName = form.friendlyName.trim()
        val parsedPort = trimmedPort.toIntOrNull()

        return ServerFormValidation(
            hostError = if (trimmedHost.isEmpty()) {
                LocalizedText(
                    zh = "请输入服务器地址",
                    en = "Please enter a server address",
                )
            } else {
                null
            },
            portError = if (parsedPort == null || parsedPort !in 1..65535) {
                LocalizedText(
                    zh = "端口需为 1–65535",
                    en = "Port must be 1–65535",
                )
            } else {
                null
            },
            friendlyNameError = if (trimmedName.length > 30) {
                LocalizedText(
                    zh = "名字最长 30 个字符",
                    en = "Name must be 1–30 chars",
                )
            } else {
                null
            },
            duplicateNameWarning = if (
                trimmedName.isNotEmpty() &&
                existingFriendlyNames.any { it.trim().equals(trimmedName, ignoreCase = true) }
            ) {
                LocalizedText(
                    zh = "这个名字已经用过",
                    en = "That name is already in use",
                )
            } else {
                null
            },
        )
    }

    fun submitOrNull(form: ServerFormState, existingFriendlyNames: Collection<String> = emptyList()): ServerFormSubmit? {
        if (!validate(form, existingFriendlyNames).canSubmit) return null
        return ServerFormSubmit(
            host = form.host.trim(),
            port = form.port.trim().toInt(),
            friendlyName = form.friendlyName.trim().takeIf { it.isNotEmpty() },
        )
    }
}
