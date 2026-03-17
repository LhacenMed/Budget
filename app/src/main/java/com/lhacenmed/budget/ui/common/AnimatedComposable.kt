package com.lhacenmed.budget.ui.common

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.lhacenmed.budget.ui.common.motion.emphasizeEasing
import com.lhacenmed.budget.ui.common.motion.materialSharedAxisXIn
import com.lhacenmed.budget.ui.common.motion.materialSharedAxisXOut

const val DURATION_ENTER = 400
const val DURATION_EXIT  = 200
const val initialOffset  = 0.10f

private val enterTween = tween<IntOffset>(durationMillis = DURATION_ENTER, easing = emphasizeEasing)
private val exitTween  = tween<IntOffset>(durationMillis = DURATION_ENTER, easing = emphasizeEasing)
private val fadeSpec   = tween<Float>(durationMillis = DURATION_EXIT)

val springSpec = spring(
    stiffness           = Spring.StiffnessMedium,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

/**
 * Primary page transition — horizontal shared axis slide + fade.
 * Uses AnimatedContentScope (Navigation 2.8+) instead of the deprecated
 * AnimatedVisibilityScope which silently ignores transition parameters.
 */
fun NavGraphBuilder.animatedComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(
    route              = route,
    arguments          = arguments,
    deepLinks          = deepLinks,
    enterTransition    = { materialSharedAxisXIn(initialOffsetX  = {  (it * initialOffset).toInt() }) },
    exitTransition     = { materialSharedAxisXOut(targetOffsetX  = { -(it * initialOffset).toInt() }) },
    popEnterTransition = { materialSharedAxisXIn(initialOffsetX  = { -(it * initialOffset).toInt() }) },
    popExitTransition  = { materialSharedAxisXOut(targetOffsetX  = {  (it * initialOffset).toInt() }) },
    content            = content
)

/** Fade-through variant — scale + fade in, fade out. */
fun NavGraphBuilder.fadeThroughComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(
    route              = route,
    arguments          = arguments,
    deepLinks          = deepLinks,
    enterTransition    = { fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)) },
    exitTransition     = { fadeOut(tween(90)) },
    popEnterTransition = { fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)) },
    popExitTransition  = { fadeOut(tween(90)) },
    content            = content
)

/** Legacy slide variant — plain slide + fade without shared axis. */
fun NavGraphBuilder.animatedComposableLegacy(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(
    route              = route,
    arguments          = arguments,
    deepLinks          = deepLinks,
    enterTransition    = { slideInHorizontally(enterTween,  initialOffsetX = {  (it * initialOffset).toInt() }) + fadeIn(fadeSpec) },
    exitTransition     = { slideOutHorizontally(exitTween,  targetOffsetX  = { -(it * initialOffset).toInt() }) + fadeOut(fadeSpec) },
    popEnterTransition = { slideInHorizontally(enterTween,  initialOffsetX = { -(it * initialOffset).toInt() }) + fadeIn(fadeSpec) },
    popExitTransition  = { slideOutHorizontally(exitTween,  targetOffsetX  = {  (it * initialOffset).toInt() }) + fadeOut(fadeSpec) },
    content            = content
)

/** Asymmetric variant — slides in, fades out; fades in, slides out on back. */
fun NavGraphBuilder.animatedComposableVariant(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(
    route              = route,
    arguments          = arguments,
    deepLinks          = deepLinks,
    enterTransition    = { slideInHorizontally(enterTween, initialOffsetX = { (it * initialOffset).toInt() }) + fadeIn(fadeSpec) },
    exitTransition     = { fadeOut(fadeSpec) },
    popEnterTransition = { fadeIn(fadeSpec) },
    popExitTransition  = { slideOutHorizontally(exitTween, targetOffsetX  = { (it * initialOffset).toInt() }) + fadeOut(fadeSpec) },
    content            = content
)

/** Bottom-sheet-style vertical slide. */
fun NavGraphBuilder.slideInVerticallyComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(
    route              = route,
    arguments          = arguments,
    deepLinks          = deepLinks,
    enterTransition    = { slideInVertically(enterTween,  initialOffsetY = {  it }) + fadeIn() },
    exitTransition     = { slideOutVertically() },
    popEnterTransition = { slideInVertically() },
    popExitTransition  = { slideOutVertically(enterTween, targetOffsetY  = {  it }) + fadeOut() },
    content            = content
)
