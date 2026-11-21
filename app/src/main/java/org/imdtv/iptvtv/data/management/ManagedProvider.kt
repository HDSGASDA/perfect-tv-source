package org.imdtv.iptvtv.data.management

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.imdtv.iptvtv.BuildConfig
import org.imdtv.iptvtv.data.db.ProviderType

/** Compare parsed origins, treating explicit HTTPS port 443 and a trailing slash as equivalent. */
object ManagedProvider {
    fun accepts(type: ProviderType, url: String, expected: String = BuildConfig.STREAMING_BASE_URL): Boolean =
        type == ProviderType.XTREAM && url.toHttpUrlOrNull() != null && url.toHttpUrlOrNull() == expected.toHttpUrlOrNull()
}
