package com.chronos.pocketos.core

import com.chronos.pocketos.core.files.SandboxFileSystem
import com.chronos.pocketos.core.settings.ProfileChoice
import com.chronos.pocketos.core.settings.SettingsCodec
import com.chronos.pocketos.core.settings.ShellSettings
import com.chronos.pocketos.core.settings.ThemeMode
import com.chronos.pocketos.core.tools.Calculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun calc(e: String) = (Calculator.evaluate(e) as? Calculator.Result.Value)?.display()

    @Test fun calculatorPrecedenceAndDecimals() {
        assertEquals("14", calc("2+3*4"))
        assertEquals("20", calc("(2+3)*4"))
        assertEquals("0.3", calc("0.1+0.2"))
        assertEquals("-5", calc("-(2+3)"))
        assertEquals("2.5", calc("5÷2"))
        assertEquals("1", calc("10 % 3"))
        assertEquals("6", calc("2×3"))
    }

    @Test fun calculatorErrors() {
        assertTrue(Calculator.evaluate("1/0") is Calculator.Result.Error)
        assertTrue(Calculator.evaluate("2+") is Calculator.Result.Error)
        assertTrue(Calculator.evaluate("(2") is Calculator.Result.Error)
        assertTrue(Calculator.evaluate("abc") is Calculator.Result.Error)
    }

    @Test fun settingsRoundTripAndTolerance() {
        val s = ShellSettings(theme = ThemeMode.DARK, cursorSpeed = 2f, profileChoice = ProfileChoice.PERFORMANCE)
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
        assertEquals(ShellSettings(), SettingsCodec.decode("not json"))
        assertEquals(ShellSettings(), SettingsCodec.decode(null))
        // Unknown keys and out-of-range values are tolerated.
        val d = SettingsCodec.decode("""{"theme":"LIGHT","cursorSpeed":99,"futureKey":1}""")
        assertEquals(ThemeMode.LIGHT, d.theme)
        assertEquals(ShellSettings.MAX_SPEED, d.cursorSpeed)
    }

    @Test fun sandboxCrudAndEscapePrevention() {
        val fs = SandboxFileSystem(tmp.newFolder("home"))
        fs.createFolder("", "Docs")
        fs.writeText("Docs/a.txt", "hello")
        assertEquals("hello", fs.readText("Docs/a.txt"))
        assertEquals(listOf("a.txt"), fs.list("Docs").map { it.name })
        fs.rename("Docs/a.txt", "b.txt")
        assertFalse(fs.resolve("Docs/a.txt").exists())
        assertEquals(5, fs.usedBytes())
        fs.delete("Docs")
        assertTrue(fs.list("").isEmpty())
        try { fs.resolve("../outside"); fail("escape allowed") } catch (_: SecurityException) {}
        try { fs.createFolder("", "a/b"); fail("slash allowed") } catch (_: IllegalArgumentException) {}
        try { fs.delete(""); fail("root delete allowed") } catch (_: java.io.IOException) {}
    }
}
