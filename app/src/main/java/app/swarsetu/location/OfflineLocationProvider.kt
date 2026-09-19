package app.swarsetu.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import app.swarsetu.mesh.protocol.LocationPayload
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Provides offline GNSS/GPS location without relying on Google Play Services.
 */
class OfflineLocationProvider(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Attempts to get a fresh location lock within the given timeout.
     * If it fails, falls back to the best last known location.
     * Returns null if no location can be determined.
     */
    @SuppressLint("MissingPermission")
    suspend fun getFreshOrCachedLocation(timeoutMs: Long = 5000L): LocationPayload? {
        if (!hasLocationPermissions(context)) return null

        // Try getting a fresh location first
        val freshLocation = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                
                try {
                    locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    // Ignore, fallback to network or cached
                }

                continuation.invokeOnCancellation {
                    locationManager.removeUpdates(listener)
                }
            }
        }

        if (freshLocation != null) {
            return LocationPayload(
                latitude = freshLocation.latitude,
                longitude = freshLocation.longitude,
                accuracyMeters = freshLocation.accuracy,
                timestamp = freshLocation.time
            )
        }

        // Fallback to last known
        return getBestCachedLocation()
    }

    @SuppressLint("MissingPermission")
    private fun getBestCachedLocation(): LocationPayload? {
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        
        for (provider in providers) {
            val location = locationManager.getLastKnownLocation(provider)
            if (location != null) {
                if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                    bestLocation = location
                }
            }
        }

        return bestLocation?.let {
            LocationPayload(
                latitude = it.latitude,
                longitude = it.longitude,
                accuracyMeters = it.accuracy,
                timestamp = it.time
            )
        }
    }

    /**
     * Emits the latest cached location, updating periodically when active.
     */
    @SuppressLint("MissingPermission")
    fun observeLocation(): Flow<LocationPayload> = callbackFlow {
        if (!hasLocationPermissions(context)) {
            close()
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(
                    LocationPayload(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        timestamp = location.time
                    )
                )
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                10000L, // 10 seconds
                10f,    // 10 meters
                listener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            // Ignore if GPS is disabled
        }
        
        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                10000L,
                10f,
                listener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            // Ignore
        }

        // Emit cached location immediately if available
        getBestCachedLocation()?.let { trySend(it) }

        awaitClose {
            locationManager.removeUpdates(listener)
        }
    }

    private fun hasLocationPermissions(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
               context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
