package com.fajar.speedtest

object AsnResolver {
    private val asnMap = mapOf(
        // Major Telcos & National ISPs
        17451 to "Biznet Networks",
        7713 to "Telkom Indonesia (IndiHome)",
        17974 to "Telkomsel",
        132061 to "Telkomsel",
        24203 to "XL Axiata",
        131740 to "XL Axiata",
        9907 to "Indosat Ooredoo Hutchison",
        23693 to "Smartfren Telecom",
        38163 to "Smartfren Telecom",
        4761 to "Moratelindo (Oxygen.id)",
        23947 to "MyRepublic Indonesia",
        131753 to "CBN Internet (Cyberindo)",
        24532 to "CBN Internet",
        38158 to "CBN Internet",
        56024 to "Link Net (First Media)",
        136052 to "PLN Icon+ (ICONNET)",
        58390 to "Bali Towerindo (Balifiber)",
        136055 to "Megavision",
        133847 to "MNC Play",
        131759 to "Transvision",
        133848 to "MNC Kabel Mediacom",
        134371 to "Citra Net",
        136050 to "Nusanet",

        // Regional & City ISPs (AS38xxx block)
        38141 to "Citra Media Internet",
        38143 to "Blueline Internet",
        38144 to "Jalawave Media",
        38145 to "Panca Teknologi",
        38146 to "Diginet Internet",
        38147 to "Inovanet",
        38148 to "Qiandra Network",
        38149 to "Ratelindo Net",
        38150 to "Telnet Indonesia",
        38155 to "Audia Net",
        38156 to "RouteLink",
        38159 to "JJNet",
        38160 to "Insprint Network",
        38161 to "Corbec Media",
        38162 to "Delta Network",
        38164 to "Buminet",
        38743 to "Abtinfosystem Network",
        38763 to "Cyber Bintan Network",
        38779 to "BMKG Network",

        // Global Tech
        15169 to "Google LLC",
        13335 to "Cloudflare, Inc.",
        8075 to "Microsoft Corporation",
        16509 to "Amazon.com, Inc."
    )

    fun resolveIsp(asnNumber: Int?): String? {
        if (asnNumber == null) return null
        return asnMap[asnNumber]
    }

    fun cleanApnicName(raw: String): String {
        var n = raw.replace(Regex("-(AS-)?(ID|AP|SG|MY)$", RegexOption.IGNORE_CASE), "")
        n = n.replace(Regex("-AS$", RegexOption.IGNORE_CASE), "")
        return n.replace("-", " ").trim().split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
