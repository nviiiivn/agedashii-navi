package online.iamprettyfamous.navi.dash

/**
 * java.lang.Math's two calls the turn banner makes (TurnBannerView.kt, shared
 * with the Android app byte for byte). Kotlin/Native has no java.lang, so the
 * name resolves here instead - the same arithmetic as :core's.
 */
internal object Math {
    fun toRadians(degrees: Double): Double = online.iamprettyfamous.navi.core.toRadians(degrees)
}
