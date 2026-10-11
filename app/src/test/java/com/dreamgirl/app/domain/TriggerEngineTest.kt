package com.dreamgirl.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class TriggerEngineTest {

    private val engine = TriggerEngine()

    private fun snap(
        hour: Int,
        minute: Int = 0,
        continuous: Int = 0,
        screenToday: Int = 100,
        pkg: String? = null,
        label: String? = null,
        sinceChat: Int? = null,
    ) = TriggerEngine.Snapshot(
        now = LocalDateTime.of(2026, 10, 11, hour, minute),
        screenOnMinutesToday = screenToday,
        continuousUseMinutes = continuous,
        foregroundPackage = pkg,
        foregroundLabel = label,
        minutesSinceLastChat = sinceChat,
    )

    @Test
    fun `quiet afternoon with nothing unusual produces no trigger`() {
        assertNull(engine.decide(snap(hour = 15, continuous = 20, sinceChat = 60), null, 0L))
    }

    @Test
    fun `deep night fires the late night rule`() {
        val d = engine.decide(snap(hour = 2, minute = 40, sinceChat = 300), null, 0L)
        assertNotNull(d)
        assertTrue(listOf(TriggerEngine.Reason.DEEP_NIGHT, TriggerEngine.Reason.LATE_NIGHT).contains(d!!.reason))
    }

    @Test
    fun `long continuous use fires the screen rule`() {
        val d = engine.decide(snap(hour = 15, continuous = 95, sinceChat = 300), null, 0L)
        assertNotNull(d)
        assertEquals(TriggerEngine.Reason.LONG_SCREEN, d!!.reason)
    }

    @Test
    fun `long gaming session is recognised as gaming`() {
        val d = engine.decide(
            snap(hour = 14, continuous = 95, pkg = "com.miHoYo.Yuanshen", label = "原神", sinceChat = 300),
            null,
            0L,
        )
        assertNotNull(d)
        assertEquals(TriggerEngine.Reason.LONG_GAME, d!!.reason)
        assertTrue(d.situationLine.contains("原神"))
    }

    @Test
    fun `situation line never contains raw package names`() {
        val d = engine.decide(
            snap(hour = 14, continuous = 120, pkg = "com.miHoYo.Yuanshen", label = "原神", sinceChat = 300),
            null,
            0L,
        )
        assertNotNull(d)
        assertFalse(d!!.situationLine.contains("com.miHoYo"))
    }

    @Test
    fun `cooldown suppresses a repeat of the same rule`() {
        val now = 1_000_000_000L
        val fired = now - 10 * 60_000L // 10 分钟前刚说过
        val d = engine.decide(snap(hour = 2, minute = 40, sinceChat = 300), fired, now)
        assertNull(d)
    }

    @Test
    fun `cooldown expires`() {
        val now = 1_000_000_000L
        val fired = now - 200 * 60_000L // 200 分钟前
        val d = engine.decide(snap(hour = 23, minute = 30, sinceChat = 300), fired, now)
        assertNotNull(d)
    }

    @Test
    fun `long silence fires the no contact rule`() {
        val d = engine.decide(snap(hour = 15, sinceChat = 10 * 60), null, 0L)
        assertNotNull(d)
        assertEquals(TriggerEngine.Reason.NO_CONTACT, d!!.reason)
    }

    @Test
    fun `long silence at 3am prefers the night rule`() {
        val d = engine.decide(snap(hour = 3, sinceChat = 10 * 60), null, 0L)
        assertNotNull(d)
        assertEquals(TriggerEngine.Reason.DEEP_NIGHT, d!!.reason)
    }

    private fun assertFalse(b: Boolean) = org.junit.Assert.assertFalse(b)
    private fun assertTrue(b: Boolean) = org.junit.Assert.assertTrue(b)
}
