package com.nuvio.app.features.player.autosync

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

// Per-request deadlines come from the loader's own budget, so the client sets none of its own.
internal actual val platformAutoSyncRangeFetcher: AutoSyncRangeFetcher? =
    KtorAutoSyncRangeFetcher(
        HttpClient(Darwin) {
            expectSuccess = false
            followRedirects = true
        },
    )
