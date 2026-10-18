package org.amission

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A position to centre the map on and where it came from. [id] changes on every request so repeated taps re-centre. */
data class LocateResult(val lat: Double, val lon: Double, val source: String, val id: Int)

object PhoneLocation {
    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPermission(context: Context): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Best available fix from the phone: a fresh GPS reading if GPS is on, else a network fix,
     * else the most recent known location. Null when nothing is available or permission is missing.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            val enabled = lm.getProviders(true)
            var loc: Location? = null
            if (LocationManager.GPS_PROVIDER in enabled) loc = fresh(context, lm, LocationManager.GPS_PROVIDER, 15_000)
            if (loc == null && LocationManager.NETWORK_PROVIDER in enabled) {
                loc = fresh(context, lm, LocationManager.NETWORK_PROVIDER, 5_000)
            }
            loc ?: enabled.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(context: Context, lm: LocationManager, provider: String, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) {
                    cont.resume(it)
                }
            }
        }
}
