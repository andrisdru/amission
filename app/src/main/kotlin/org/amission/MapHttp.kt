package org.amission

import android.content.Context
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * One-time setup of the map SDK's network layer. The free OpenFreeMap service asks apps that
 * download areas for offline use to send a User-Agent that identifies the app and to use at most
 * 4 parallel requests, so every map request here follows that.
 */
object MapSdk {
    const val SOURCE_URL = "https://github.com/andrisdru/amission"
    private const val MAX_PARALLEL_PER_HOST = 4
    private var ready = false

    @Synchronized
    fun init(context: Context) {
        MapLibre.getInstance(context)
        if (ready) return
        ready = true
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
        } catch (e: Exception) {
            "dev"
        }
        val userAgent = "amission/$version (INAV mission planner; +$SOURCE_URL)"
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_PARALLEL_PER_HOST })
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
        HttpRequestUtil.setOkHttpClient(client)
    }
}
