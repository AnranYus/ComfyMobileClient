package com.comfymobile.data.image

import coil3.ImageLoader
import com.comfymobile.data.platform.PlatformContext
import io.ktor.client.HttpClient

expect fun createComfyImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
): ImageLoader
