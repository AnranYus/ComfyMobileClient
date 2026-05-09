package com.comfymobile.presentation.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerFormValidatorTest {

    @Test fun valid_default_port_and_host_can_submit() {
        val form = ServerFormState(host = " 192.168.1.5 ")
        val validation = ServerFormValidator.validate(form)
        val submit = ServerFormValidator.submitOrNull(form)

        assertTrue(validation.canSubmit)
        assertEquals("192.168.1.5", submit?.host)
        assertEquals(8188, submit?.port)
        assertNull(submit?.friendlyName)
    }

    @Test fun host_is_required_after_trim() {
        val validation = ServerFormValidator.validate(ServerFormState(host = " "))
        assertFalse(validation.canSubmit)
        assertNotNull(validation.hostError)
    }

    @Test fun port_must_be_integer_in_range() {
        listOf("", "0", "65536", "abc").forEach { port ->
            val validation = ServerFormValidator.validate(ServerFormState(host = "server", port = port))
            assertFalse(validation.canSubmit, "port=$port should fail")
            assertNotNull(validation.portError)
        }

        listOf("1", "8188", "65535").forEach { port ->
            val validation = ServerFormValidator.validate(ServerFormState(host = "server", port = port))
            assertNull(validation.portError, "port=$port should pass")
        }
    }

    @Test fun friendly_name_max_length_is_enforced_after_trim() {
        val ok = ServerFormValidator.validate(
            ServerFormState(host = "server", friendlyName = "x".repeat(30)),
        )
        val tooLong = ServerFormValidator.validate(
            ServerFormState(host = "server", friendlyName = "x".repeat(31)),
        )

        assertNull(ok.friendlyNameError)
        assertNotNull(tooLong.friendlyNameError)
        assertFalse(tooLong.canSubmit)
    }

    @Test fun duplicate_friendly_name_warns_but_does_not_block_submit() {
        val validation = ServerFormValidator.validate(
            form = ServerFormState(host = "server", friendlyName = " Studio Mac "),
            existingFriendlyNames = listOf("studio mac"),
        )

        assertTrue(validation.canSubmit)
        assertNotNull(validation.duplicateNameWarning)
    }
}
