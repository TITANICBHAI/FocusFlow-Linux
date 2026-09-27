package com.focusflow.ui.components

/**
 * The one logical vertical movement used by both ScrollState and LazyListState
 * arrow handling. The shared modifier converts this value through
 * LocalDensity before calling the existing state's suspend scroll operation.
 */
internal const val DEFAULT_VERTICAL_SCROLL_STEP_DP = 48

internal enum class ArrowScrollKey {
    ARROW_UP,
    ARROW_DOWN,
    OTHER
}

internal enum class ArrowScrollEventType {
    KEY_DOWN,
    KEY_UP
}

internal enum class ArrowScrollDirection {
    UP,
    DOWN
}

/**
 * A platform-neutral input description used as the test seam for the shared
 * policy. Compose's post-child onKeyEvent path supplies these values in Batch 2.
 */
internal data class ArrowScrollInput(
    val key: ArrowScrollKey,
    val eventType: ArrowScrollEventType = ArrowScrollEventType.KEY_DOWN,
    val isCtrlPressed: Boolean = false,
    val childConsumed: Boolean = false
)

internal data class ArrowScrollIntent(
    val direction: ArrowScrollDirection,
    val stepDp: Int = DEFAULT_VERTICAL_SCROLL_STEP_DP
)

/**
 * Returns an intent only for an unconsumed, unmodified Arrow Up/Down key-down.
 *
 * The `childConsumed` flag models Compose's post-child handler contract:
 * editable children keep their normal caret/selection behavior and the parent
 * scroll owner does not receive a second scroll command.
 */
internal fun resolveArrowScrollIntent(input: ArrowScrollInput): ArrowScrollIntent? {
    if (input.childConsumed) return null
    if (input.eventType != ArrowScrollEventType.KEY_DOWN) return null
    if (input.isCtrlPressed) return null

    return when (input.key) {
        ArrowScrollKey.ARROW_UP ->
            ArrowScrollIntent(ArrowScrollDirection.UP)
        ArrowScrollKey.ARROW_DOWN ->
            ArrowScrollIntent(ArrowScrollDirection.DOWN)
        ArrowScrollKey.OTHER -> null
    }
}

/**
 * Pure clamping seam for the pixel movement eventually sent to either
 * ScrollState.scrollBy or LazyListState.scrollBy.
 */
internal fun nextArrowScrollOffset(
    currentPx: Int,
    maxPx: Int,
    direction: ArrowScrollDirection,
    stepPx: Int
): Int {
    val safeMax = maxPx.coerceAtLeast(0)
    val safeStep = stepPx.coerceAtLeast(0)
    val delta = if (direction == ArrowScrollDirection.UP) -safeStep else safeStep
    return (currentPx + delta).coerceIn(0, safeMax)
}