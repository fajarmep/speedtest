package com.fajar.speedtest

object AsnResolver {
    private val asnMap = mapOf(
        17451 to "Biznet Networks",
        7713 to "Telkom Indonesia (IndiHome)",
        17974 to "Telkomsel",
        132061 to "Telkomsel",
        24203 to "XL Axiata",
        131740 to "XL Axiata",
        9907 to "Indosat Ooredoo Hutchison",
        23693 to "Smartfren Telecom",
        4761 to "Moratelindo (Oxygen.id)",
        23947 to "MyRepublic Indonesia",
        131753 to "CBN Internet (Cyberindo)",
        24532 to "CBN Internet",
        56024 to "Link Net (First Media)",
        136052 to "PLN Icon+ (ICONNET)",
        58390 to "Bali Towerindo (Balifiber)",
        136055 to "Megavision",
        133847 to "MNC Play",
        131759 to "Transvision",
        133848 to "MNC Kabel Mediacom",
        134371 to "Citra Net",
        136050 to "Nusanet",
        15169 to "Google LLC",
        13335 to "Cloudflare, Inc.",
        8075 to "Microsoft Corporation",
        16509 to "Amazon.com, Inc."
    )

    fun resolveIsp(asnNumber: Int?): String? {
        if (asnNumber == null) return null
        return asnMap[asnNumber]
    }
}
