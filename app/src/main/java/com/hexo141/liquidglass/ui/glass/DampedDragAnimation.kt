package com.hexo141.liquidglass.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlinx.coroutines.launch

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val slideAnimationSpec =
        tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private companion object {
        /** Upper bound for holding the pressed scale while a release snap settles. */
        const val RELEASE_SETTLE_TIMEOUT_MS = 600L
    }

    private val velocityTracker = VelocityTracker()
    private var dragValueJob: Job? = null

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    /** True while a finger owns the capsule; external tab changes must not snap it mid-drag. */
    var isDragging: Boolean = false
        private set

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                isDragging = true
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                isDragging = false
                onDragStopped()
                release()
            },
            onDragCancel = {
                isDragging = false
                onDragStopped()
                release()
            }
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                // Wait for the snap to settle so the pressed glass keeps its original feel, but
                // never leave it enlarged forever if a gesture ended without a target animation.
                withTimeoutOrNull(RELEASE_SETTLE_TIMEOUT_MS) {
                    snapshotFlow { valueAnimation.value }
                        .filter { abs(it - valueAnimation.targetValue) < threshold }
                        .first()
                }
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        // While the finger is down the capsule must track it 1:1. A spring here makes it trail
        // behind fast drags; the animated snap is reserved for the release in onDragStopped.
        dragValueJob?.cancel()
        dragValueJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.snapTo(targetValue)
            updateVelocity()
        }
    }

    /**
     * Moves the capsule to [value] with a plain eased slide: used for tab changes, which should
     * glide across the bar instead of popping (no press, no scale, no spring overshoot).
     */
    fun slideToValue(value: Float) {
        // During a drag the capsule stays wherever the finger put it; the only snap is the one
        // triggered by onDragStopped after the finger is lifted or the gesture is cancelled.
        if (isDragging) return
        val targetValue = value.coerceIn(valueRange)
        // A release snap must win over an in-flight drag update, otherwise the capsule can stop
        // between two tabs after a fast drag.
        dragValueJob?.cancel()
        animationScope.launch {
            mutatorMutex.mutate {
                valueAnimation.animateTo(targetValue, slideAnimationSpec)
            }
        }
    }

    fun animateToValue(value: Float) {
        dragValueJob?.cancel()
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
