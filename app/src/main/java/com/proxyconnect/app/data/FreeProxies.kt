package com.proxyconnect.app.data

/**
 * Pre-populated free proxy servers for demo/testing.
 * These are public free proxies - they may be slow or go offline.
 */
object FreeProxies {

    fun getDefaults(): List<ProxyProfile> = listOf(
        ProxyProfile(
            id = "free-us-1",
            name = "\uD83C\uDDFA\uD83C\uDDF8 United States",
            host = "198.59.191.234",
            port = 8080,
            autoConnect = false
        ),
        ProxyProfile(
            id = "free-de-1",
            name = "\uD83C\uDDE9\uD83C\uDDEA Germany",
            host = "138.201.113.2",
            port = 1080,
            autoConnect = false
        ),
        ProxyProfile(
            id = "free-nl-1",
            name = "\uD83C\uDDF3\uD83C\uDDF1 Netherlands",
            host = "51.158.68.68",
            port = 8811,
            autoConnect = false
        ),
        ProxyProfile(
            id = "free-uk-1",
            name = "\uD83C\uDDEC\uD83C\uDDE7 United Kingdom",
            host = "51.75.126.150",
            port = 1080,
            autoConnect = false
        ),
        ProxyProfile(
            id = "free-fr-1",
            name = "\uD83C\uDDEB\uD83C\uDDF7 France",
            host = "51.178.195.50",
            port = 1080,
            autoConnect = false
        ),
        ProxyProfile(
            id = "free-ca-1",
            name = "\uD83C\uDDE8\uD83C\uDDE6 Canada",
            host = "192.99.34.64",
            port = 1080,
            autoConnect = false
        ),
        ProxyProfile(
            id = "free-jp-1",
            name = "\uD83C\uDDEF\uD83C\uDDF5 Japan",
            host = "103.105.78.214",
            port = 8080,
            autoConnect = false
        ),
        ProxyProfile(
            id = "free-sg-1",
            name = "\uD83C\uDDF8\uD83C\uDDEC Singapore",
            host = "103.253.72.74",
            port = 1080,
            autoConnect = false
        ),
        ProxyProfile(
            id = "free-br-1",
            name = "\uD83C\uDDE7\uD83C\uDDF7 Brazil",
            host = "177.93.36.74",
            port = 4145,
            autoConnect = false
        ),
        ProxyProfile(
            id = "free-au-1",
            name = "\uD83C\uDDE6\uD83C\uDDFA Australia",
            host = "103.216.82.18",
            port = 6667,
            autoConnect = false
        )
    )
}
