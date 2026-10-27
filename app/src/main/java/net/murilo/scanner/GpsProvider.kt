package net.murilo.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import kotlin.math.abs

/** Posição GPS do aparelho -> Dash (lat/lon/velocidade) + AVISO DE RADAR por proximidade. */
object GpsProvider {
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    private var lastSpokenRadar = ""

    fun hasPerm(ctx: Context) = Build.VERSION.SDK_INT < 23 ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, dash: Dash) {
        if (listener != null) return
        if (!hasPerm(ctx)) return
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                dash.gpsLat = loc.latitude; dash.gpsLon = loc.longitude
                dash.gpsSpeedKmh = loc.speed * 3.6f; dash.gpsHasFix = true
                if (Feat.on("radar")) checkRadar(loc, dash) else dash.radarDistM = -1f
            }
            override fun onProviderDisabled(p: String) {}
            override fun onProviderEnabled(p: String) {}
            @Deprecated("deprecated") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, l)
            try { m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, l) } catch (_: Exception) {}
            listener = l; lm = m
        } catch (_: Exception) {}
    }

    private fun checkRadar(loc: Location, dash: Dash) {
        RadarDb.onLocation(loc.latitude, loc.longitude)   // baixa a base se precisar
        val n = RadarDb.nearest(loc.latitude, loc.longitude)
        if (n != null && n.second <= 700.0 && ahead(loc, n.first)) {
            dash.radarDistM = n.second.toFloat(); dash.radarMaxSpeed = n.first.maxSpeed
            val id = "${n.first.lat},${n.first.lon}"
            if (id != lastSpokenRadar && n.second < 600.0) {
                lastSpokenRadar = id
                val m = (n.second / 50.0).toInt() * 50
                val sp = if (n.first.maxSpeed > 0) ", máxima ${n.first.maxSpeed}" else ""
                Speaker.say("Radar a $m metros$sp")
            }
        } else {
            dash.radarDistM = -1f
            if (n == null || n.second > 900.0) lastSpokenRadar = ""   // liberou p/ avisar de novo
        }
    }

    /** Radar está à frente? Se parado/sem rumo, avisa mesmo assim (dentro do raio). */
    private fun ahead(loc: Location, r: RadarDb.Radar): Boolean {
        if (!loc.hasBearing() || loc.speed * 3.6f < 15f) return true
        val toR = bearing(loc.latitude, loc.longitude, r.lat, r.lon)
        val diff = abs(((toR - loc.bearing) % 360f + 540f) % 360f - 180f)
        return diff < 80f
    }

    private fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Float {
        val dLo = Math.toRadians(lo2 - lo1); val y = Math.sin(dLo) * Math.cos(Math.toRadians(la2))
        val x = Math.cos(Math.toRadians(la1)) * Math.sin(Math.toRadians(la2)) -
            Math.sin(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.cos(dLo)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    fun stop() {
        try { listener?.let { lm?.removeUpdates(it) } } catch (_: Exception) {}
        listener = null
    }
}
