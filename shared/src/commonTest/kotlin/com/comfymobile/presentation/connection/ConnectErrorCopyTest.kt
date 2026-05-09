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

    @Test fun no_active_server_primary_action_is_dismiss_not_retry() {
        // Per @Lily PR #19 review (`4413981846`) blocker 2 + Ores
        // convergence: clicking "Choose / 去选择" must NOT dispatch
        // Retry (which would loop back to Lost(NO_ACTIVE_SERVER)).
        // The Lookup contract pins this in the data layer.
        val lookup = ConnectErrorCopy.lookup(ConnectError.NO_ACTIVE_SERVER)
        assertEquals(ConnectErrorCopy.PrimaryAction.DISMISS, lookup.primaryAction)
    }

    @Test fun no_active_server_copy_and_action_lock_together() {
        // Belt + braces test (per @Lily PR #19 review `9005f999`):
        // make sure copy and behavior cannot drift apart in a future
        // refactor — if someone bumps the CTA back to "Retry" but
        // forgets the action, this test fails; conversely if they
        // change the action without renaming the CTA, this also fails.
        val lookup = ConnectErrorCopy.lookup(ConnectError.NO_ACTIVE_SERVER)
        // CTA text must NOT be "Retry" / "重试" while action is
        // anything other than RETRY.
        if (lookup.primaryAction != ConnectErrorCopy.PrimaryAction.RETRY) {
            assertTrue(
                lookup.primaryCtaEn != "Retry" && lookup.primaryCtaZh != "重试",
                "primaryAction != RETRY but CTA text is the default Retry copy: $lookup",
            )
        }
        // For NO_ACTIVE_SERVER specifically: CTA == Choose AND action == DISMISS.
        assertEquals("Choose", lookup.primaryCtaEn)
        assertEquals("去选择", lookup.primaryCtaZh)
        assertEquals(ConnectErrorCopy.PrimaryAction.DISMISS, lookup.primaryAction)
    }

    @Test fun choose_button_for_no_active_server_routes_to_dismiss_not_retry() {
        // End-to-end of the data → routing → callback chain that
        // ConnectErrorView relies on. Per @Lily PR #19 review
        // `4413981846` blocker 2: clicking the primary CTA when
        // error == NO_ACTIVE_SERVER must NOT dispatch Retry; it must
        // fire the dismiss callback (which closes the error sheet
        // and reveals the connect form / history).
        var retryCalls = 0
        var dismissCalls = 0
        val lookup = ConnectErrorCopy.lookup(ConnectError.NO_ACTIVE_SERVER)
        val handler = ConnectErrorCopy.primaryClickHandler(
            primaryAction = lookup.primaryAction,
            onRetry = { retryCalls++ },
            onDismiss = { dismissCalls++ },
        )
        handler()
        assertEquals(0, retryCalls, "Retry must not be dispatched on NO_ACTIVE_SERVER primary CTA")
        assertEquals(1, dismissCalls)
    }

    @Test fun retry_button_for_other_errors_routes_to_retry() {
        // Default direction also locked: errors with default
        // PrimaryAction.RETRY route to onRetry.
        for (error in listOf(
            ConnectError.TIMEOUT,
            ConnectError.REFUSED,
            ConnectError.NOT_COMFYUI,
            ConnectError.WRONG_PORT_404,
            ConnectError.UNKNOWN,
        )) {
            var retryCalls = 0
            var dismissCalls = 0
            val lookup = ConnectErrorCopy.lookup(error)
            val handler = ConnectErrorCopy.primaryClickHandler(
                primaryAction = lookup.primaryAction,
                onRetry = { retryCalls++ },
                onDismiss = { dismissCalls++ },
            )
            handler()
            assertEquals(1, retryCalls, "$error should route to onRetry")
            assertEquals(0, dismissCalls)
        }
    }

    @Test fun unchanged_errors_keep_default_retry_action() {
        // Default-action regression: errors that haven't been
        // explicitly given a primaryAction should still be RETRY,
        // because that's the Lookup default.
        for (error in listOf(
            ConnectError.FORMAT,
            ConnectError.TIMEOUT,
            ConnectError.REFUSED,
            ConnectError.TLS_HANDSHAKE,
            ConnectError.NOT_COMFYUI,
            ConnectError.WRONG_PORT_404,
            ConnectError.UNKNOWN,
        )) {
            val lookup = ConnectErrorCopy.lookup(error)
            assertEquals(
                ConnectErrorCopy.PrimaryAction.RETRY,
                lookup.primaryAction,
                "Expected $error to keep default RETRY action, got ${lookup.primaryAction}",
            )
        }
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
