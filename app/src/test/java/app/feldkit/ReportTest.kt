package app.feldkit

import app.feldkit.hash.HashAlgo
import app.feldkit.offload.DestResult
import app.feldkit.offload.DestStatus
import app.feldkit.offload.FileResult
import app.feldkit.offload.Report
import app.feldkit.offload.ReportInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportTest {
    private fun info() = ReportInfo(
        "FKTEMPO", "Pixel", 0L, 12.5, listOf("Downloads/FeldKit", "SD card"), listOf(HashAlgo.XXH64, HashAlgo.MD5), true,
        listOf(
            FileResult(listOf("Songs", "Groove, 127.wav"), 5_000_000, mapOf(HashAlgo.XXH64 to "aabbccdd00112233", HashAlgo.MD5 to "d41d8cd98f00b204e9800998ecf8427e"),
                listOf(DestResult("Downloads/FeldKit", DestStatus.VERIFIED, "Groove, 127.wav"), DestResult("SD card", DestStatus.MISMATCH, "Groove, 127.wav", "read-back differs"))),
            FileResult(listOf("bad.bin"), 10, null, emptyList(), error = "read failed"),
        ),
    )

    @Test fun csvQuotesAndStatuses() {
        val lines = Report.csv(info()).trim().lines()
        assertEquals("file,size_bytes,modified,result: Downloads/FeldKit,result: SD card,xxh64,md5", lines[0])
        assertEquals("\"Songs/Groove, 127.wav\",5000000,,verified,MISMATCH,aabbccdd00112233,d41d8cd98f00b204e9800998ecf8427e", lines[1])
        assertEquals("bad.bin,10,,source error: read failed,source error: read failed,,", lines[2])
    }

    @Test fun summaryCounts() {
        val s = Report.summary(info())
        assertEquals(2, s.files); assertEquals(0, s.ok); assertEquals(2, s.problems)
    }
}
