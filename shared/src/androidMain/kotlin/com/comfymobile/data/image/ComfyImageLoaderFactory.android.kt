package com.comfymobile.data.image

import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.comfymobile.data.platform.PlatformContext
import io.ktor.client.HttpClient

actual fun createComfyImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
): ImageLoader =
    ImageLoader.Builder(context.androidContext)
        .components {
            add(KtorNetworkFetcherFactory(httpClient))
        }
        .build()
