package com.focusflow.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollKeyboardPolicyTest {
    @Test
    fun `only unconsumed key down arrow events produce intents`() {
        assertEquals(
            ArrowScrollIntent(ArrowScrollDirection.UP),
            resolveArrowScrollIntent(ArrowScrollInput(ArrowScrollKey.ARROW_UP))
        )
        assertEquals(
            ArrowScrollIntent(ArrowScrollDirection.DOWN),
            resolveArrowScrollIntent(ArrowScrollInput(ArrowScrollKey.ARROW_DOWN))
        )

        assertNull(
            resolveArrowScrollIntent(
                ArrowScrollInput(
                    key = ArrowScrollKey.ARROW_UP,
                    eventType = ArrowScrollEventType.KEY_UP
                )
            )
        )
        assertNull(
            resolveArrowScrollIntent(
                ArrowScrollInput(key = ArrowScrollKey.OTHER)
            )
        )
    }

    @Test
    fun `ctrl arrows are left for existing navigation shortcuts`() {
        assertNull(
            resolveArrowScrollIntent(
                ArrowScrollInput(
                    key = ArrowScrollKey.ARROW_DOWN,
                    isCtrlPressed = true
                )
            )
        )
    }

    @Test
    fun `editable child consumption prevents parent scroll`() {
        assertNull(
            resolveArrowScrollIntent(
                ArrowScrollInput(
                    key = ArrowScrollKey.ARROW_UP,
                    childConsumed = true
                )
            )
        )
    }

    @Test
    fun `both directions use the centralized logical step`() {
        val up = resolveArrowScrollIntent(ArrowScrollInput(ArrowScrollKey.ARROW_UP))
        val down = resolveArrowScrollIntent(ArrowScrollInput(ArrowScrollKey.ARROW_DOWN))

        assertEquals(DEFAULT_VERTICAL_SCROLL_STEP_DP, up?.stepDp)
        assertEquals(DEFAULT_VERTICAL_SCROLL_STEP_DP, down?.stepDp)
        assertEquals(ArrowScrollDirection.UP, up?.direction)
        assertEquals(ArrowScrollDirection.DOWN, down?.direction)
    }

    @Test
    fun `pixel movement clamps at both scroll boundaries`() {
        assertEquals(
            0,
            nextArrowScrollOffset(
                currentPx = 10,
                maxPx = 100,
                direction = ArrowScrollDirection.UP,
                stepPx = 48
            )
        )
        assertEquals(
            58,
            nextArrowScrollOffset(
                currentPx = 10,
                maxPx = 100,
                direction = ArrowScrollDirection.DOWN,
                stepPx = 48
            )
        )
        assertEquals(
            100,
            nextArrowScrollOffset(
                currentPx = 90,
                maxPx = 100,
                direction = ArrowScrollDirection.DOWN,
                stepPx = 48
            )
        )
        assertEquals(
            0,
            nextArrowScrollOffset(
                currentPx = 0,
                maxPx = 100,
                direction = ArrowScrollDirection.UP,
                stepPx = 48
            )
        )
    }
}