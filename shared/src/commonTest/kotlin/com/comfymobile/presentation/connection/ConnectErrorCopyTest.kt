package com.comfymobile.presentation.connection

import com.comfymobile.data.network.ConnectError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the user-visible copy contract for [ConnectError] entries
 * that have product implications beyond a generic "connection
 * failed" message.
 *
 * Currently focused on `NO_ACTIVE_SERVER` — per @Lily / @Ores PR #19
 * convergence (`4413957569` / `b522a9f3`) that error MUST surface
 * with the "Choose" CTA, NOT the default "Retry", because retrying
 * without a server makes no sense; the right affordance is
 * routing the user back to the connect form / history list.
 */
class ConnectErrorCopyTest {

    @Test fun no_active_server_lookup_uses_choose_CTA_not_retry() {
        val lookup = ConnectErrorCopy.lookup(ConnectError.NO_ACTIVE_SERVER)
        assertEquals("去选择", lookup.primaryCtaZh)
        assertEquals("Choose", lookup.primaryCtaEn)
    }

    @Test fun no_active_server_lookup_has_first_class_title_distinct_from_unknown() {
        val noServer = ConnectErrorCopy.lookup(ConnectError.NO_ACTIVE_SERVER)
        val unknown = ConnectErrorCopy.lookup(ConnectError.UNKNOWN)
        assertEquals("没有选中的服务器", noServer.titleZh)
        assertEquals("No active server", noServer.titleEn)
        // Distinguishability — must not collapse into UNKNOWN's
        // generic copy.
        assertTrue(noServer.titleEn != unknown.titleEn)
        assertTrue(noServer.bodyEn != unknown.bodyEn)
    }

    @Test fun no_active_server_body_matches_locked_short_versions() {
        // Per @Ores / @Lily final convergence in PR #19 thread:
        // - zh: "还没有选好要连的服务器。从历史里挑一个，或在表单里输入 IP。"
        // - en: "No server is selected. Pick one from history, or enter an IP again."
        val lookup = ConnectErrorCopy.lookup(ConnectError.NO_ACTIVE_SERVER)
        assertEquals(
            "还没有选好要连的服务器。从历史里挑一个，或在表单里输入 IP。",
            lookup.bodyZh,
        )
        assertEquals(
            "No server is selected. Pick one from history, or enter an IP again.",
            lookup.bodyEn,
        )
    }
}
