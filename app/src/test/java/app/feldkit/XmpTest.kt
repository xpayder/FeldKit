package app.feldkit

import app.feldkit.photo.Xmp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpTest {
    private val lightroom = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
   xmlns:dc="http://purl.org/dc/elements/1.1/" xmp:Rating="2" crs:Exposure2012="+0.35" crs:Contrast2012="+12">
   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Harbour &amp; dusk</rdf:li></rdf:Alt></dc:title>
   <dc:subject><rdf:Bag><rdf:li>harbour</rdf:li><rdf:li>blue hour</rdf:li></rdf:Bag></dc:subject>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

    @Test fun parsesAttributeAndElementForms() {
        val d = Xmp.parse(lightroom)
        assertEquals(2, d.rating); assertEquals(null, d.label); assertEquals("Harbour & dusk", d.title); assertEquals(listOf("harbour", "blue hour"), d.keywords)
        val e = Xmp.parse("<rdf:Description><xmp:Rating>4</xmp:Rating><xmp:Label>Red</xmp:Label></rdf:Description>")
        assertEquals(4, e.rating); assertEquals("Red", e.label)
    }

    @Test fun updateTouchesOnlyRatingAndLabel() {
        val out = Xmp.update(lightroom, 5, "Green")
        assertEquals(5, Xmp.parse(out).rating); assertEquals("Green", Xmp.parse(out).label)
        assertTrue(out.contains("crs:Exposure2012=\"+0.35\"")); assertTrue(out.contains("Harbour &amp; dusk"))
        // changing only the rating leaves everything else identical
        assertEquals(lightroom.replace("xmp:Rating=\"2\"", "xmp:Rating=\"1\""), Xmp.update(lightroom, 1, null))
        // removing a label
        assertEquals(null, Xmp.parse(Xmp.update(out, null, null)).label); assertEquals(5, Xmp.parse(Xmp.update(out, null, null)).rating)
    }

    @Test fun addsMissingFields() {
        val bare = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"r\"><rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>"
        val out = Xmp.update(bare, -1, "Red")
        assertEquals(-1, Xmp.parse(out).rating); assertEquals("Red", Xmp.parse(out).label); assertTrue(out.contains("xmlns:xmp="))
        val withEl = "<rdf:Description xmlns:xmp=\"$${Xmp.NS_XMP}\"><xmp:Rating>3</xmp:Rating></rdf:Description>"
        assertEquals(1, Xmp.parse(Xmp.update(withEl, 1, null)).rating)
    }

    @Test fun createdSidecarRoundTrips() {
        val s = Xmp.create(4, "Blue")
        assertEquals(4, Xmp.parse(s).rating); assertEquals("Blue", Xmp.parse(s).label)
        assertEquals(3, Xmp.parse(Xmp.update(s, 3, "Blue")).rating)
    }

    @Test fun findsEmbeddedPacket() {
        val jpeg = ByteArray(50) + lightroom.toByteArray() + ByteArray(20)
        assertEquals(2, Xmp.parse(Xmp.findEmbedded(jpeg)!!).rating)
    }
}
