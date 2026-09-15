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

    /** Support / feedback address used by Help & Support. */
    const val SUPPORT_EMAIL = "yaaratech@gmail.com"
}
