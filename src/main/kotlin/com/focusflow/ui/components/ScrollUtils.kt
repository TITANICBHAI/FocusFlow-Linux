package com.focusflow.ui.components

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.focusflow.ui.theme.*
import kotlinx.coroutines.launch

// ── Style ──────────────────────────────────────────────────────────────────────
// unhoverColor = subtle track always visible so users know they can scroll.
// hoverColor   = full purple when the cursor moves near — handled natively by
//                Compose Desktop's VerticalScrollbar without any alpha modifier.
// While scrolling, unhoverColor is boosted so the thumb is clearly visible.
//
// NO alpha modifier is used — that was hiding the native hover behaviour.

@Composable
private fun ffStyle(isScrollInProgress: Boolean) = LocalScrollbarStyle.current.copy(
    thickness     = 8.dp,
    minimalHeight = 48.dp,
    shape         = RoundedCornerShape(4.dp),
    unhoverColor  = Purple80.copy(alpha = if (isScrollInProgress) 0.75f else 0.22f),
    hoverColor    = Purple80.copy(alpha = 0.95f)
)

// ── Public drop-in replacements ────────────────────────────────────────────────

/** Vertical scrollbar for a [ScrollState].
 *  - Subtle at rest so users see the track and know the screen scrolls.
 *  - Lights up instantly when the cursor moves near (native Compose Desktop hover).
 *  - Bright while scrolling.
 */
@Composable
fun FfVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = modifier,
        style    = ffStyle(scrollState.isScrollInProgress)
    )
}

/** Vertical scrollbar for a [LazyListState]. */
@Composable
fun FfVerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = modifier,
        style    = ffStyle(listState.isScrollInProgress)
    )
}

/** Vertical scrollbar for a [LazyGridState]. */
@Composable
fun FfVerticalScrollbar(
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(gridState),
        modifier = modifier,
        style    = ffStyle(gridState.isScrollInProgress)
    )
}

/** Horizontal scrollbar for a [ScrollState]. */
@Composable
fun FfHorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = modifier,
        style    = ffStyle(scrollState.isScrollInProgress)
    )
}

// ── Keyboard scrolling ────────────────────────────────────────────────────────

/**
 * Adds Arrow Up/Down scrolling to an existing vertical scroll owner.
 *
 * The handler is deliberately a post-child [onKeyEvent] handler. Text fields
 * and other editable children therefore keep first opportunity to consume
 * their arrow keys. Standard Compose pointer input, including mouse-wheel
 * scrolling, remains provided by the existing vertical scrollable itself.
 *
 * Top-level screen owners should pass `requestFocus = true` so a newly entered
 * screen can scroll immediately. Nested dialogs and pickers should leave it
 * false when they contain an input that should retain initial focus.
 */
fun Modifier.arrowScroll(
    scrollState: ScrollState,
    requestFocus: Boolean = false
): Modifier = arrowScrollOwner(requestFocus) { deltaPx ->
    scrollState.scrollBy(deltaPx)
}

/** Arrow scrolling for an existing [LazyListState]. */
fun Modifier.arrowScroll(
    listState: LazyListState,
    requestFocus: Boolean = false
): Modifier = arrowScrollOwner(requestFocus) { deltaPx ->
    listState.scrollBy(deltaPx)
}

/** Arrow scrolling for an existing [LazyGridState]. */
fun Modifier.arrowScroll(
    gridState: LazyGridState,
    requestFocus: Boolean = false
): Modifier = arrowScrollOwner(requestFocus) { deltaPx ->
    gridState.scrollBy(deltaPx)
}

private fun Modifier.arrowScrollOwner(
    requestFocus: Boolean,
    scrollBy: suspend (Float) -> Unit
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    if (requestFocus) {
        LaunchedEffect(focusRequester) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { event ->
            val key = when (event.key) {
                Key.DirectionUp -> ArrowScrollKey.ARROW_UP
                Key.DirectionDown -> ArrowScrollKey.ARROW_DOWN
                else -> ArrowScrollKey.OTHER
            }
            val intent = resolveArrowScrollIntent(
                ArrowScrollInput(
                    key = key,
                    eventType = if (event.type == KeyEventType.KeyDown) {
                        ArrowScrollEventType.KEY_DOWN
                    } else {
                        ArrowScrollEventType.KEY_UP
                    },
                    isCtrlPressed = event.isCtrlPressed
                )
            ) ?: return@onKeyEvent false

            val stepPx = with(density) { intent.stepDp.dp.toPx() }
            val deltaPx = if (intent.direction == ArrowScrollDirection.UP) {
                -stepPx
            } else {
                stepPx
            }
            scope.launch { scrollBy(deltaPx) }
            true
        }
}

// ── Layout helpers ─────────────────────────────────────────────────────────────

/** Column with a vertical scrollbar always visible on the right edge. */
@Composable
fun ScrollbarColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .arrowScroll(scrollState)
                .padding(contentPadding),
            content = content
        )
        FfVerticalScrollbar(
            scrollState = scrollState,
            modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

/** Box wrapping a LazyColumn with a vertical scrollbar always visible on the right edge. */
@Composable
fun LazyScrollbarBox(
    modifier: Modifier = Modifier,
    lazyListState: LazyListState,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        content()
        FfVerticalScrollbar(
            listState = lazyListState,
            modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

/** Box with both vertical and horizontal scrollbars. */
@Composable
fun DualScrollbarBox(
    modifier: Modifier = Modifier,
    vScrollState: ScrollState,
    hScrollState: ScrollState,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(hScrollState)
                .verticalScroll(vScrollState)
                .arrowScroll(vScrollState)
        ) {
            content()
        }
        FfVerticalScrollbar(
            scrollState = vScrollState,
            modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 16.dp)
        )
        FfHorizontalScrollbar(
            scrollState = hScrollState,
            modifier    = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(end = 16.dp)
        )
    }
}
