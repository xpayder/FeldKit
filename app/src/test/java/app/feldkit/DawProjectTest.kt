package app.feldkit

import app.feldkit.audio.DawProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** Real projects (copied from the user's X8 into ~/FeldKit-test-projects) plus a small generated Live set and REAPER project. */
class DawProjectTest {
    private val base = File(System.getProperty("user.home"), "FeldKit-test-projects")

    @Test fun realAbletonSet() {
        val f = File(base, "1deaEDM Project/1deaEDM.als"); assumeTrue(f.exists())
        val i = DawProject.parse("als", f.inputStream().buffered())!!
        println("ALS: ${i.daw} ${i.version} tempo=${i.tempo} meter=${i.meter}"); i.sections.forEach { println("  ${it.title}: ${it.items.take(8)}") }
        assertEquals("Ableton Live", i.daw); assertEquals(124.0, i.tempo!!, 0.001); assertEquals("4/4", i.meter)
        val tr = i.sections.first { it.title.startsWith("Tracks") }.items
        assertTrue(tr.contains("MIDI · 1-MIDI")); assertEquals(8, tr.size)
        assertTrue(i.sections.any { it.title == "Plug-ins" && it.items.any { p -> p.startsWith("REF SEND") } })
    }

    @Test fun realFlStudioProject() {
        val f = File(base, "cosa d#maj 160/cosa d#maj 160.flp"); assumeTrue(f.exists())
        val i = DawProject.parse("flp", f.inputStream().buffered())!!
        println("FLP: ${i.daw} ${i.version} tempo=${i.tempo}"); i.sections.forEach { println("  ${it.title}: ${it.items.take(8)}") }
        assertEquals("FL Studio", i.daw); assertEquals("24.2.1.4184", i.version); assertEquals(160.0, i.tempo!!, 0.001)
        assertTrue(i.sections.first { it.title.startsWith("Channels") }.items.containsAll(listOf("kick", "snare", "808")))
        assertTrue(i.sections.any { it.title.startsWith("Samples used") })
    }

    @Test fun everyFlBackupParses() {
        val dir = File(base, "cosa d#maj 160/Backup"); assumeTrue(dir.exists())
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".flp") }) assertNotNull(f.name, DawProject.parse("flp", f.inputStream().buffered()))
    }

    @Test fun reaperProject() {
        val rpp = """<REAPER_PROJECT 0.1 "7.11/macOS-arm64" 1727000000
  TEMPO 98.5 3 4
  <TRACK {AAA}
    NAME "Drums"
    <FXCHAIN
      <VST "VST3: Pro-Q 3 (FabFilter)" "FabFilter Pro-Q 3.vst3" 0 "" 1234
      >
    >
    <ITEM
      <SOURCE WAVE
        FILE "/Users/x/Audio/kick loop.wav"
      >
    >
  >
  <TRACK {BBB}
    NAME "Bass"
  >
>"""
        val i = DawProject.parse("rpp", ByteArrayInputStream(rpp.toByteArray()))!!
        assertEquals("REAPER", i.daw); assertEquals(98.5, i.tempo!!, 0.001); assertEquals("3/4", i.meter)
        assertEquals(listOf("Drums", "Bass"), i.sections.first { it.title.startsWith("Tracks") }.items)
        assertEquals(listOf("Pro-Q 3 (FabFilter)"), i.sections.first { it.title == "Plug-ins" }.items)
        assertEquals(listOf("kick loop.wav"), i.sections.first { it.title.startsWith("Media") }.items)
    }

    @Test fun notAProject() {
        assertEquals(null, DawProject.parse("rpp", ByteArrayInputStream("hello".toByteArray())))
        assertEquals(null, DawProject.parse("flp", ByteArrayInputStream(ByteArray(40))))
        val bad = java.io.ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write("not xml at all".toByteArray()) } }.toByteArray()
        assertEquals(null, DawProject.parse("als", ByteArrayInputStream(bad)))
    }
}
