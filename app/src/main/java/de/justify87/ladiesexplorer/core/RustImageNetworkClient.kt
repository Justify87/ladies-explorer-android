package de.justify87.ladiesexplorer.core

import coil3.network.NetworkClient
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import okio.Buffer

/** Coil caches and decodes; Rust owns the HTTP transport. */
internal class RustImageNetworkClient(
    private val load: suspend (String) -> DisplayImage,
) : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (NetworkResponse) -> T,
    ): T {
        require(request.method == "GET" && request.body == null) { "Only image GET requests are supported" }
        val started = System.currentTimeMillis()
        val image = load(request.url)
        val body = NetworkResponseBody(Buffer().write(image.bytes))
        val headers = NetworkHeaders.Builder().apply {
            add("Content-Type", image.mime)
            add("Content-Length", image.bytes.size.toString())
        }.build()
        try {
            return block(NetworkResponse(
                code = 200,
                requestMillis = started,
                responseMillis = System.currentTimeMillis(),
                headers = headers,
                body = body,
            ))
        } finally {
            body.close()
        }
    }
}
