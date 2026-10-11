package app.feldkit

import app.feldkit.audio.AudioMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A WAV built chunk by chunk (as a field recorder / loop tool would) must give back every field. */
class AudioMetaTest {
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); o.write(id.toByteArray()); o.write(le(4).putInt(body.size).array()); o.write(body); if (body.size % 2 == 1) o.write(0); return o.toByteArray()
    }
    private fun fixed(s: String, n: Int) = s.toByteArray().copyOf(n)

    private fun wav(): ByteArray {
        val fmt = le(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16).array()
        val bext = ByteArrayOutputStream().apply {
            write(fixed("Boom mic, wind at the door", 256)); write(fixed("Sound Devices MixPre", 32)); write(fixed("REF001", 32)); write(fixed("2026-10-09", 10)); write(fixed("14:03:22", 8))
            write(le(8).putLong(48000L * 3600 * 14 + 48000L * 60 * 3 + 48000L * 22).array()); write(ByteArray(2 + 64 + 190 + 16))   // version, UMID, reserved
        }.toByteArray()
        val ixml = """<?xml version="1.0"?><BWFXML><PROJECT>Harbour doc</PROJECT><SCENE>12A</SCENE><TAPE>R04</TAPE><TAKE>3</TAKE><CIRCLED>TRUE</CIRCLED>
            <NOTE>Gull flyover</NOTE><SPEED><TIMECODE_RATE>25/1</TIMECODE_RATE></SPEED><TRACK_LIST><TRACK><NAME>Boom</NAME></TRACK><TRACK><NAME>Lav 1</NAME></TRACK></TRACK_LIST></BWFXML>""".toByteArray()
        val smpl = le(36 + 24).putInt(0).putInt(0).putInt(0).putInt(60).putInt(0).putInt(0).putInt(0).putInt(1).putInt(0).array()   // root note 60, one loop
        val acid = le(24).putInt(0x02 or 0x08).putShort(57).putShort(0).putInt(0).putInt(8).putShort(4).putShort(4).putFloat(127.35f).array()
        val cue = le(4 + 24 * 2).putInt(2).array()
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray()); write(chunk("fmt ", fmt)); write(chunk("bext", bext)); write(chunk("iXML", ixml))
            write(chunk("data", ByteArray(100_001)))                                   // odd size: padded
            write(chunk("smpl", smpl)); write(chunk("acid", acid)); write(chunk("cue ", cue))
        }.toByteArray()
        return "RIFF".toByteArray() + le(4).putInt(body.size).array() + body
    }

    @Test fun readsEveryField() {
        val rows = AudioMeta.parseWav(ByteArrayInputStream(wav())).associate { it.label to it.value }
        assertEquals("Boom mic, wind at the door", rows["Description"])
        assertEquals("Sound Devices MixPre", rows["Originator"])
        assertEquals("2026-10-09 14:03:22", rows["Recorded"])
        assertEquals("Harbour doc", rows["Project"]); assertEquals("12A", rows["Scene"]); assertEquals("R04", rows["Tape / roll"])
        assertEquals("3 (circled)", rows["Take"]); assertEquals("Gull flyover", rows["Note"]); assertEquals("Boom, Lav 1", rows["Tracks"])
        assertEquals("14:03:22.000  (25 fps)", rows["Start timecode"])
        assertEquals("C4 (MIDI 60)", rows["Root note"]); assertEquals("1", rows["Sampler loops"])
        assertEquals("A3", rows["Root note (Acid)"]); assertEquals("8 beats in 4/4", rows["Loop length"]); assertEquals("127.35 BPM", rows["Acid tempo"])
        assertEquals("2", rows["Markers"])
    }

    @Test fun plainWavGivesNothingAndNeverThrows() {
        val fmt = le(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16).array()
        val body = "WAVE".toByteArray() + chunk("fmt ", fmt) + chunk("data", ByteArray(1000))
        assertTrue(AudioMeta.parseWav(ByteArrayInputStream("RIFF".toByteArray() + le(4).putInt(body.size).array() + body)).isEmpty())
        assertTrue(AudioMeta.parseWav(ByteArrayInputStream(ByteArray(5))).isEmpty())
        assertTrue(AudioMeta.parseWav(ByteArrayInputStream(wav().copyOf(300))).isNotEmpty() || true)   // truncated file: must not crash
    }
}
