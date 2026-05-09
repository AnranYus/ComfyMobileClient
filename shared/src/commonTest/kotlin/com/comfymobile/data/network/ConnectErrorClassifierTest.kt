package com.comfymobile.data.network

import com.comfymobile.data.network.ConnectAttemptOutcome.PlatformErrorTag
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectErrorClassifierTest {

    @Test fun format_rejected_maps_to_FORMAT() {
        val (err, ctx) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.FormatRejected("port out of range"),
        )
        assertEquals(ConnectError.FORMAT, err)
        assertEquals("port out of range", ctx.description)
    }

    @Test fun platform_tag_timeout_short_circuits_to_TIMEOUT() {
        val (err, _) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.NetworkFailure(
                message = "ignored",
                platformErrorTag = PlatformErrorTag.Timeout,
            ),
        )
        assertEquals(ConnectError.TIMEOUT, err)
    }

    @Test fun platform_tag_refused_short_circuits_to_REFUSED() {
        val (err, _) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.NetworkFailure(
                message = "anything",
                platformErrorTag = PlatformErrorTag.Refused,
            ),
        )
        assertEquals(ConnectError.REFUSED, err)
    }

    @Test fun platform_tag_tls_handshake_short_circuits_to_TLS_HANDSHAKE() {
        val (err, _) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.NetworkFailure(
                message = "anything",
                platformErrorTag = PlatformErrorTag.TlsHandshake,
            ),
        )
        assertEquals(ConnectError.TLS_HANDSHAKE, err)
    }

    @Test fun substring_sniff_recognises_timeout_message() {
        val (err, _) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.NetworkFailure(message = "Read timed out"),
        )
        assertEquals(ConnectError.TIMEOUT, err)
    }

    @Test fun substring_sniff_recognises_econnrefused() {
        val (err, _) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.NetworkFailure(message = "Connection refused (ECONNREFUSED)"),
        )
        assertEquals(ConnectError.REFUSED, err)
    }

    @Test fun substring_sniff_recognises_ssl_handshake() {
        val (err, _) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.NetworkFailure(message = "SSL handshake failed"),
        )
        assertEquals(ConnectError.TLS_HANDSHAKE, err)
    }

    @Test fun unrecognized_message_falls_back_to_UNKNOWN() {
        val (err, ctx) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.NetworkFailure(message = "something weird happened"),
        )
        assertEquals(ConnectError.UNKNOWN, err)
        assertEquals("something weird happened", ctx.description)
    }

    @Test fun http_404_maps_to_WRONG_PORT_404() {
        val (err, ctx) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.HttpResponse(statusCode = 404, bodyLooksLikeComfyUi = false),
        )
        assertEquals(ConnectError.WRONG_PORT_404, err)
        assertEquals(404, ctx.statusCode)
    }

    @Test fun http_200_with_unrecognized_body_maps_to_NOT_COMFYUI() {
        val (err, ctx) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.HttpResponse(statusCode = 200, bodyLooksLikeComfyUi = false),
        )
        assertEquals(ConnectError.NOT_COMFYUI, err)
        assertEquals(200, ctx.statusCode)
    }

    @Test fun http_500_falls_back_to_UNKNOWN_with_status_code() {
        val (err, ctx) = ConnectErrorClassifier.classify(
            ConnectAttemptOutcome.HttpResponse(statusCode = 500, bodyLooksLikeComfyUi = false),
        )
        assertEquals(ConnectError.UNKNOWN, err)
        assertEquals(500, ctx.statusCode)
    }

    @Test fun unknown_failure_outcome_maps_to_UNKNOWN() {
        val (err, _) = ConnectErrorClassifier.classify(ConnectAttemptOutcome.UnknownFailure)
        assertEquals(ConnectError.UNKNOWN, err)
    }
}
