package com.fajar.speedtest

import kotlin.math.*

data class SpeedServer(
    val id: String,
    val name: String,
    val region: String,
    val host: String,
    val latitude: Double,
    val longitude: Double,
    val pingUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    var distanceKm: Double? = null
) {
    fun getDisplayNameWithDistance(): String {
        return if (distanceKm != null && distanceKm!! > 0) {
            val dist = if (distanceKm!! < 10) String.format("%.1f km", distanceKm) else String.format("%.0f km", distanceKm)
            "$name • $dist"
        } else {
            name
        }
    }
}

object ServerCatalog {

    fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Radius Bumi dlm km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun getServersWithDistance(userLat: Double?, userLon: Double?): List<SpeedServer> {
        val list = rawServers.map { it.copy() }
        if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
            list.forEach { server ->
                if (server.id != "auto") {
                    server.distanceKm = calculateDistanceKm(userLat, userLon, server.latitude, server.longitude)
                }
            }
            val nearestConcrete = list.filter { it.id != "auto" }.minByOrNull { it.distanceKm ?: Double.MAX_VALUE }
            list.find { it.id == "auto" }?.distanceKm = nearestConcrete?.distanceKm
        }
        return list
    }

    private val rawServers = listOf(
        SpeedServer(
            id = "auto",
            name = "Auto (Server Terdekat)",
            region = "Anycast Nearest Edge",
            host = "speed.cloudflare.com",
            latitude = 0.0,
            longitude = 0.0,
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "id_cgk",
            name = "Jakarta, Indonesia (CGK)",
            region = "Indonesia Barat",
            host = "speed.cloudflare.com",
            latitude = -6.1256,
            longitude = 106.6558,
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "id_sub",
            name = "Surabaya, Indonesia (SUB)",
            region = "Indonesia Timur",
            host = "speed.cloudflare.com",
            latitude = -7.3798,
            longitude = 112.7874,
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "sg_sin",
            name = "Singapura (SIN Edge)",
            region = "Asia Tenggara",
            host = "speed.cloudflare.com",
            latitude = 1.3644,
            longitude = 103.9915,
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "jp_nrt",
            name = "Tokyo, Jepang (NRT Edge)",
            region = "Asia Timur",
            host = "speed.cloudflare.com",
            latitude = 35.7720,
            longitude = 140.3929,
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "au_syd",
            name = "Sydney, Australia (SYD)",
            region = "Oceania",
            host = "speed.cloudflare.com",
            latitude = -33.9399,
            longitude = 151.1753,
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "eu_fra",
            name = "Frankfurt, Jerman (FRA)",
            region = "Eropa Tengah",
            host = "speed.cloudflare.com",
            latitude = 50.0379,
            longitude = 8.5622,
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "us_sjc",
            name = "San Jose, USA (SJC Edge)",
            region = "Amerika Utara",
            host = "speed.cloudflare.com",
            latitude = 37.3639,
            longitude = -121.9289,
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        )
    )
}
