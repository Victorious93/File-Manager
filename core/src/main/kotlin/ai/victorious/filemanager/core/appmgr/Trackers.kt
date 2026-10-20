package ai.victorious.filemanager.core.appmgr

/**
 * Self-built tracker signature list: class-name prefixes of widely documented analytics,
 * advertising, crash-reporting and attribution SDKs. Compiled independently from each SDK's
 * public package names (facts about public APIs), NOT copied from Exodus Privacy or AppManager.
 * Credit: the idea of class-prefix tracker detection is popularised by Exodus Privacy
 * (https://exodus-privacy.eu.org); we did not use their dataset.
 */
data class TrackerSignature(val name: String, val category: Category, val prefixes: List<String>) {
    enum class Category { ANALYTICS, ADVERTISING, CRASH_REPORTING, ATTRIBUTION, PROFILING, LOCATION, SOCIAL }
}

object TrackerSignatures {
    val ALL: List<TrackerSignature> = listOf(
        TrackerSignature("Google Firebase Analytics", TrackerSignature.Category.ANALYTICS, listOf("com.google.firebase.analytics.", "com.google.android.gms.measurement.")),
        TrackerSignature("Google AdMob", TrackerSignature.Category.ADVERTISING, listOf("com.google.android.gms.ads.")),
        TrackerSignature("Google Analytics", TrackerSignature.Category.ANALYTICS, listOf("com.google.android.gms.analytics.")),
        TrackerSignature("Google Crashlytics", TrackerSignature.Category.CRASH_REPORTING, listOf("com.google.firebase.crashlytics.", "com.crashlytics.")),
        TrackerSignature("Google Tag Manager", TrackerSignature.Category.ANALYTICS, listOf("com.google.android.gms.tagmanager.")),
        TrackerSignature("Facebook Analytics", TrackerSignature.Category.ANALYTICS, listOf("com.facebook.appevents.")),
        TrackerSignature("Facebook Audience Network", TrackerSignature.Category.ADVERTISING, listOf("com.facebook.ads.")),
        TrackerSignature("Facebook Login/Share", TrackerSignature.Category.SOCIAL, listOf("com.facebook.login.", "com.facebook.share.")),
        TrackerSignature("AppsFlyer", TrackerSignature.Category.ATTRIBUTION, listOf("com.appsflyer.")),
        TrackerSignature("Adjust", TrackerSignature.Category.ATTRIBUTION, listOf("com.adjust.sdk.")),
        TrackerSignature("Branch", TrackerSignature.Category.ATTRIBUTION, listOf("io.branch.")),
        TrackerSignature("Kochava", TrackerSignature.Category.ATTRIBUTION, listOf("com.kochava.")),
        TrackerSignature("Singular", TrackerSignature.Category.ATTRIBUTION, listOf("com.singular.sdk.")),
        TrackerSignature("Flurry", TrackerSignature.Category.ANALYTICS, listOf("com.flurry.")),
        TrackerSignature("Mixpanel", TrackerSignature.Category.ANALYTICS, listOf("com.mixpanel.")),
        TrackerSignature("Amplitude", TrackerSignature.Category.ANALYTICS, listOf("com.amplitude.")),
        TrackerSignature("Segment", TrackerSignature.Category.ANALYTICS, listOf("com.segment.analytics.")),
        TrackerSignature("Braze (Appboy)", TrackerSignature.Category.PROFILING, listOf("com.appboy.", "com.braze.")),
        TrackerSignature("OneSignal", TrackerSignature.Category.PROFILING, listOf("com.onesignal.")),
        TrackerSignature("CleverTap", TrackerSignature.Category.PROFILING, listOf("com.clevertap.")),
        TrackerSignature("Leanplum", TrackerSignature.Category.PROFILING, listOf("com.leanplum.")),
        TrackerSignature("Sentry", TrackerSignature.Category.CRASH_REPORTING, listOf("io.sentry.")),
        TrackerSignature("Bugsnag", TrackerSignature.Category.CRASH_REPORTING, listOf("com.bugsnag.")),
        TrackerSignature("Instabug", TrackerSignature.Category.CRASH_REPORTING, listOf("com.instabug.")),
        TrackerSignature("ACRA", TrackerSignature.Category.CRASH_REPORTING, listOf("org.acra.")),
        TrackerSignature("Microsoft App Center", TrackerSignature.Category.CRASH_REPORTING, listOf("com.microsoft.appcenter.")),
        TrackerSignature("New Relic", TrackerSignature.Category.ANALYTICS, listOf("com.newrelic.agent.")),
        TrackerSignature("Yandex AppMetrica", TrackerSignature.Category.ANALYTICS, listOf("com.yandex.metrica.", "io.appmetrica.")),
        TrackerSignature("Unity Ads", TrackerSignature.Category.ADVERTISING, listOf("com.unity3d.ads.", "com.unity3d.services.")),
        TrackerSignature("AppLovin", TrackerSignature.Category.ADVERTISING, listOf("com.applovin.")),
        TrackerSignature("ironSource", TrackerSignature.Category.ADVERTISING, listOf("com.ironsource.")),
        TrackerSignature("Vungle / Liftoff", TrackerSignature.Category.ADVERTISING, listOf("com.vungle.")),
        TrackerSignature("Chartboost", TrackerSignature.Category.ADVERTISING, listOf("com.chartboost.")),
        TrackerSignature("InMobi", TrackerSignature.Category.ADVERTISING, listOf("com.inmobi.")),
        TrackerSignature("Mintegral", TrackerSignature.Category.ADVERTISING, listOf("com.mbridge.msdk.")),
        TrackerSignature("Pangle (ByteDance)", TrackerSignature.Category.ADVERTISING, listOf("com.bytedance.sdk.openadsdk.")),
        TrackerSignature("Tapjoy", TrackerSignature.Category.ADVERTISING, listOf("com.tapjoy.")),
        TrackerSignature("StartApp / Start.io", TrackerSignature.Category.ADVERTISING, listOf("com.startapp.")),
        TrackerSignature("MoPub", TrackerSignature.Category.ADVERTISING, listOf("com.mopub.")),
        TrackerSignature("Huawei Ads", TrackerSignature.Category.ADVERTISING, listOf("com.huawei.hms.ads.")),
        TrackerSignature("Huawei Analytics", TrackerSignature.Category.ANALYTICS, listOf("com.huawei.hms.analytics.")),
        TrackerSignature("Foursquare Pilgrim", TrackerSignature.Category.LOCATION, listOf("com.foursquare.pilgrim.")),
        TrackerSignature("Twitter Kit", TrackerSignature.Category.SOCIAL, listOf("com.twitter.sdk.")),
        TrackerSignature("Matomo", TrackerSignature.Category.ANALYTICS, listOf("org.matomo.sdk.")),
        TrackerSignature("Heap", TrackerSignature.Category.ANALYTICS, listOf("com.heapanalytics.")),
        TrackerSignature("comScore", TrackerSignature.Category.ANALYTICS, listOf("com.comscore.")),
    )
}

data class TrackerHit(val tracker: TrackerSignature, val matchedClasses: List<String>)

object TrackerScanner {
    /**
     * Matches class names (from the APK's DEX class list or the manifest's component names)
     * against [signatures]. Matching is by package prefix only, so a class named
     * `com.facebook.adsfoo.X` does not count as `com.facebook.ads.`.
     */
    fun scan(classNames: Collection<String>, signatures: List<TrackerSignature> = TrackerSignatures.ALL): List<TrackerHit> =
        signatures.mapNotNull { sig ->
            val hits = classNames.filter { c -> sig.prefixes.any { c.startsWith(it) } }.distinct().sorted()
            if (hits.isEmpty()) null else TrackerHit(sig, hits)
        }

    /** DEX descriptors `Lcom/foo/Bar;` -> `com.foo.Bar`. */
    fun descriptorToClass(d: String): String = d.removePrefix("L").removeSuffix(";").replace('/', '.')

    /** Tracker components (from the manifest) that the blocker can disable in one go. */
    fun trackerComponents(components: List<AppComponent>, signatures: List<TrackerSignature> = TrackerSignatures.ALL): List<AppComponent> =
        components.filter { c -> signatures.any { s -> s.prefixes.any { c.className.startsWith(it) } } }
}
