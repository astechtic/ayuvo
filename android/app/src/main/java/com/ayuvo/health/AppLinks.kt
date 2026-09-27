package com.ayuvo.health

/**
 * Every external Ayuvo URL and contact address in one place. The website is a static site
 * (Firebase Hosting, clean URLs); the apps never call it — these are only opened in the
 * browser or the mail app when the user taps a row.
 */
object AppLinks {
    const val SITE_URL = "https://ayuvo-health.web.app"
    const val PRIVACY_URL = "$SITE_URL/privacy"
    const val TERMS_URL = "$SITE_URL/terms"
    const val SUPPORT_URL = "$SITE_URL/support"
    const val PRIVACY_FIRST_URL = "$SITE_URL/privacy-first"
    const val OPEN_SOURCE_URL = "$SITE_URL/open-source"
    const val NUTRIENTS_URL = "$SITE_URL/nutrients"

    /** The website's guide for one nutrient (`learn_slug` of the metric catalog, docs/nutrients.md §5). */
    fun nutrientUrl(slug: String): String = "$NUTRIENTS_URL/$slug"

    /** Public source repository (MIT). Opened only when the user taps a link. */
    const val GITHUB_URL = "https://github.com/astechtic/ayuvo"

    /** Support / feedback address used by Help & Support. */
    const val SUPPORT_EMAIL = "yaaratech@gmail.com"
}
