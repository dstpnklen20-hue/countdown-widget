package com.claudecode.countdown.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/**
 * One set of soft motions for the whole app: gentle springs without bounce and an "emphasized"
 * ease, so screens and controls move alike.
 */
object Motion {
    /** Material's emphasized-decelerate curve: quick start, long soft landing. */
    private val Soft = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val SoftOut = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 180
    const val MEDIUM = 320

    fun <T> gentleSpring(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> soft(duration: Int = MEDIUM): FiniteAnimationSpec<T> = tween(duration, easing = Soft)
    fun <T> softOut(duration: Int = SHORT): FiniteAnimationSpec<T> = tween(duration, easing = SoftOut)

    /** Switching sections: the new one fades in while growing slightly, like TickTick. */
    fun sectionChange(): ContentTransform =
        (fadeIn(soft(MEDIUM)) + scaleIn(soft(MEDIUM), initialScale = 0.98f)) togetherWith fadeOut(softOut(120))

    /** A pushed screen (task details, trash, settings pages) slides in from the right. */
    val push: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(soft<IntOffset>(MEDIUM)) { it / 5 } + fadeIn(soft(MEDIUM))
    }
    val pushExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(softOut(150))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(soft(MEDIUM))
    }
    val pop: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(softOut<IntOffset>(200)) { it / 5 } + fadeOut(softOut(200))
    }

    /** Buttons that appear and disappear (e.g. the "+" button). */
    val popIn: EnterTransition get() = scaleIn(gentleSpring(), initialScale = 0.6f) + fadeIn(soft(SHORT))
    val popOut: ExitTransition get() = scaleOut(softOut(), targetScale = 0.6f) + fadeOut(softOut())
}
