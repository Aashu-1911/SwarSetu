package app.swarsetu.location

import android.location.Location
import kotlin.math.roundToInt

object LocationUtils {

    /**
     * Calculates the distance in meters between two locations.
     */
    fun calculateDistanceMeters(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(startLat, startLng, endLat, endLng, results)
        return results[0]
    }

    /**
     * Calculates the initial bearing from start to end location in degrees (0.0 to 360.0).
     */
    fun calculateBearing(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
    ): Float {
        val results = FloatArray(2)
        Location.distanceBetween(startLat, startLng, endLat, endLng, results)
        val bearing = results[1]
        // Normalize to 0-360
        return if (bearing < 0) bearing + 360f else bearing
    }

    /**
     * Converts a bearing in degrees to a 16-point compass direction (e.g., "N", "NNE", "NE").
     */
    fun getCardinalDirection(bearing: Float): String {
        val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW", "N")
        // Each sector is 22.5 degrees (360 / 16)
        val index = ((bearing + 11.25) / 22.5).toInt()
        return directions[index % 16]
    }
}
