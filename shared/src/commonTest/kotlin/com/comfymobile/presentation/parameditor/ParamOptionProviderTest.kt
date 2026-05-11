package com.comfymobile.presentation.parameditor

import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.domain.node.Source
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ParamOptionProviderTest {

    @Test fun comfy_provider_propagates_cancellation() = runTest {
        val provider = ComfyParamOptionProvider(
            ComfyHttpClient(
                baseUrl = "http://192.168.1.10:8188",
                client = HttpClient(
                    MockEngine {
                        throw CancellationException("option load cancelled")
                    }
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                },
            )
        )

        assertFailsWith<CancellationException> {
            provider.load(
                ParamOptionRequest(
                    classType = "CheckpointLoaderSimple",
                    paramName = "ckpt_name",
                    source = Source.ModelFolder("checkpoints"),
                )
            )
        }
    }
}
