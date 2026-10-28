package dev.wahh.alarmshell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PhantomThemeTest {
    @Test
    fun `default theme exposes every rendered slot`() {
        val theme = PhantomTheme.default()
        PhantomTheme.COLOR_SLOTS.forEach { slot -> assertNotNull(theme.color(slot)) }
        assertEquals("TABBED", theme.renderWindowShape)
        assertEquals("SQUARED", theme.renderButtonShape)
        assertEquals(0, theme.fontSizeOffset)
    }

    @Test
    fun `missing slots and malformed documents are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PhantomTheme.parse("""{"windowShape":"TABBED"}""") }
        assertThrows(IllegalArgumentException::class.java) { PhantomTheme.parse("not json") }
    }

    @Test
    fun `color overrides keep every other slot`() {
        val theme = PhantomTheme.default().withColor("accent", PhantomColor.fromHex("FF0055", 80))
        assertEquals("FF0055", "%06X".format(theme.color("accent").argb and 0xFFFFFF))
        assertEquals(PhantomTheme.default().color("text"), theme.color("text"))
    }
}
