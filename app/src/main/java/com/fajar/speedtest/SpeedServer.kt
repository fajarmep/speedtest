package com.fajar.speedtest

data class SpeedServer(
    val id: String,
    val name: String,
    val region: String,
    val host: String,
    val pingUrl: String,
    val downloadUrl: String,
    val uploadUrl: String
)

object ServerCatalog {
    val servers = listOf(
        SpeedServer(
            id = "auto",
            name = "Auto (Terdekat / Nearest)",
            region = "Anycast Global Edge",
            host = "speed.cloudflare.com",
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "id_cgk",
            name = "Jakarta, Indonesia (CGK)",
            region = "Asia Southeast",
            host = "speed.cloudflare.com",
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "sg_sin",
            name = "Singapura (SIN Edge)",
            region = "Asia Southeast",
            host = "speed.cloudflare.com",
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "jp_nrt",
            name = "Tokyo, Jepang (NRT Edge)",
            region = "Asia East",
            host = "speed.cloudflare.com",
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "au_syd",
            name = "Sydney, Australia (SYD)",
            region = "Oceania",
            host = "speed.cloudflare.com",
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "eu_fra",
            name = "Frankfurt, Jerman (FRA)",
            region = "Europe Central",
            host = "speed.cloudflare.com",
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        ),
        SpeedServer(
            id = "us_sjc",
            name = "San Jose, USA (SJC Edge)",
            region = "North America West",
            host = "speed.cloudflare.com",
            pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000",
            uploadUrl = "https://speed.cloudflare.com/__up"
        )
    )
}
