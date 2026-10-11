package app.feldkit.photo

/**
 * Reading and surgically editing the parts of an XMP packet that photo software shares: star rating (xmp:Rating, -1 = rejected),
 * colour label (xmp:Label), title, description and keywords. An existing packet is edited in place so that everything else in it
 * (develop settings, history, other namespaces) stays byte for byte as it was.
 */
object Xmp {
    class Data(val rating: Int?, val label: String?, val title: String?, val description: String?, val keywords: List<String>)

    const val NS_XMP = "http://ns.adobe.com/xap/1.0/"

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun attrOrElement(xml: String, name: String): String? {
        Regex("""\b${Regex.escape(name)}\s*=\s*("([^"]*)"|'([^']*)')""").find(xml)?.let { return unescape(it.groupValues[2].ifEmpty { it.groupValues[3] }) }
        Regex("""<${Regex.escape(name)}(\s[^>]*)?>([^<]*)</${Regex.escape(name)}>""").find(xml)?.let { return unescape(it.groupValues[2].trim()) }
        return null
    }

    private fun altText(xml: String, name: String): String? {
        val m = Regex("""<$name(\s[^>]*)?>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return attrOrElement(xml, name)
        return Regex("""<rdf:li[^>]*>([^<]*)</rdf:li>""").find(m.groupValues[2])?.groupValues?.get(1)?.let { unescape(it.trim()) } ?: unescape(m.groupValues[2].trim()).takeIf { it.isNotEmpty() }
    }

    fun parse(xml: String): Data {
        val keywords = Regex("""<dc:subject(\s[^>]*)?>(.*?)</dc:subject>""", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?.let { m -> Regex("""<rdf:li[^>]*>([^<]*)</rdf:li>""").findAll(m.groupValues[2]).map { unescape(it.groupValues[1].trim()) }.filter { it.isNotEmpty() }.toList() } ?: emptyList()
        return Data(
            rating = attrOrElement(xml, "xmp:Rating")?.trim()?.toDoubleOrNull()?.toInt(),
            label = attrOrElement(xml, "xmp:Label")?.takeIf { it.isNotEmpty() },
            title = altText(xml, "dc:title"), description = altText(xml, "dc:description"), keywords = keywords,
        )
    }

    /** A new sidecar carrying just a rating and a label. */
    fun create(rating: Int?, label: String?): String = buildString {
        append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"FeldKit\">\n <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("  <rdf:Description rdf:about=\"\" xmlns:xmp=\"$NS_XMP\"")
        if (rating != null) append(" xmp:Rating=\"$rating\"")
        if (!label.isNullOrEmpty()) append(" xmp:Label=\"${escape(label)}\"")
        append("/>\n </rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>\n")
    }

    /**
     * [existing] with the rating and label set (null label removes it; null rating leaves the rating alone). Only the Rating and
     * Label values are touched; if they are missing they are added to the first rdf:Description.
     */
    fun update(existing: String, rating: Int?, label: String?): String {
        var x = existing
        if (rating != null) x = setValue(x, "xmp:Rating", rating.toString())
        x = if (label.isNullOrEmpty()) removeValue(x, "xmp:Label") else setValue(x, "xmp:Label", escape(label))
        return x
    }

    private fun setValue(xml: String, name: String, value: String): String {
        val attr = Regex("""(\b${Regex.escape(name)}\s*=\s*)("[^"]*"|'[^']*')""")
        if (attr.containsMatchIn(xml)) return attr.replaceFirst(xml, "$1\"" + Regex.escapeReplacement(value) + "\"")
        val el = Regex("""(<${Regex.escape(name)}(?:\s[^>]*)?>)[^<]*(</${Regex.escape(name)}>)""")
        if (el.containsMatchIn(xml)) return el.replaceFirst(xml, "$1" + Regex.escapeReplacement(value) + "$2")
        // not there: add it as an attribute of the first rdf:Description
        val tag = Regex("""<rdf:Description\b[^>]*?(/?)>""").find(xml) ?: return xml
        val open = tag.value
        val ns = if (!xml.contains("xmlns:xmp=")) " xmlns:xmp=\"$NS_XMP\"" else ""
        val insertAt = tag.range.last - (if (tag.groupValues[1].isNotEmpty()) 1 else 0)
        return xml.substring(0, insertAt) + "$ns $name=\"$value\"" + xml.substring(insertAt)
    }

    private fun removeValue(xml: String, name: String): String {
        var x = xml
        x = Regex("""\s+${Regex.escape(name)}\s*=\s*("[^"]*"|'[^']*')""").replaceFirst(x, "")
        x = Regex("""[ \t]*<${Regex.escape(name)}(?:\s[^>]*)?>[^<]*</${Regex.escape(name)}>[ \t]*\n?""").replaceFirst(x, "")
        return x
    }

    /** Labels the common photo programs know. */
    val LABELS = listOf("Red", "Yellow", "Green", "Blue", "Purple")

    /** The XMP packet embedded in a JPEG (APP1) or any file that stores it as plain text, or null. */
    fun findEmbedded(bytes: ByteArray, limit: Int = bytes.size): String? {
        val start = indexOf(bytes, "<x:xmpmeta".toByteArray(), limit); if (start < 0) return null
        val end = indexOf(bytes, "</x:xmpmeta>".toByteArray(), limit, start); if (end < 0) return null
        return String(bytes, start, end + 12 - start, Charsets.UTF_8)
    }

    private fun indexOf(b: ByteArray, p: ByteArray, limit: Int, from: Int = 0): Int {
        var i = from
        while (i + p.size <= limit) { var k = 0; while (k < p.size && b[i + k] == p[k]) k++; if (k == p.size) return i; i++ }
        return -1
    }
}
