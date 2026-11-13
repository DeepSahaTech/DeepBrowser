package com.deepbrowser.tv.browser

/** Built-in ad/tracker hosts (bare domains, suffix-matched). Path rules belong to the EasyList engine. */
object BuiltInHosts {
    private val hosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "2mdn.net", "adsystem.com", "adnxs.com", "adsrvr.org", "advertising.com", "adform.net", "criteo.com",
        "criteo.net", "taboola.com", "outbrain.com", "pubmatic.com", "rubiconproject.com", "openx.net",
        "casalemedia.com", "smartadserver.com", "moatads.com", "scorecardresearch.com", "quantserve.com",
        "amazon-adsystem.com", "media.net", "yieldmo.com", "bidswitch.net", "contextweb.com", "lijit.com",
        "sharethrough.com", "teads.tv", "tremorhub.com", "springserve.com", "spotxchange.com", "adcolony.com",
        // found injecting the fake "confirm you're not a robot / scan QR" overlay and ad beacons on streaming pages
        "erjw7px373983fgovelfjpnnnu6.cfd", "doomsarchaic.com", "alypinehubbly.cyou", "vidy.st",
        "popads.net", "popcash.net", "propellerads.com", "onclickads.net", "adsterra.com", "exoclick.com",
        "juicyads.com", "trafficjunky.net", "clickadu.com", "hilltopads.net", "adskeeper.com", "mgid.com",
        "revcontent.com", "zedo.com", "admaven.com", "ad-maven.com", "a-ads.com", "coinzilla.io",
        "histats.com", "statcounter.com", "hotjar.com", "mixpanel.com", "segment.io", "fullstory.com",
        "facebook.net", "connect.facebook.net", "analytics.twitter.com", "ads-twitter.com", "ads.linkedin.com",
        "bat.bing.com", "clarity.ms", "mc.yandex.ru",
        "adservices.google.com", "ads.youtube.com", "static.doubleclick.net", "imasdk.googleapis.com",
        "serving-sys.com", "adition.com", "advertserve.com", "bannerflow.com", "betweendigital.com",
        "onetag-sys.com", "adsafeprotected.com", "doubleverify.com", "ipredictive.com", "richaudience.com",
        "unrulymedia.com", "33across.com", "indexww.com", "sovrn.com", "gumgum.com", "triplelift.com",
    )

    /** True when [host] or any parent domain is listed. */
    fun matches(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    internal val all: Set<String> get() = hosts
}
