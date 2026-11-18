package com.hq.retrocam.util

import java.io.ByteArrayOutputStream

/**
 * Google "Motion Photo" v1 container (also read by MIUI / HyperOS gallery as a moving photo):
 * a JPEG whose XMP points at an MP4 appended after the image data.
 */
object MotionPhoto {
    fun build(jpeg: ByteArray, mp4: ByteArray, presentationUs: Long): ByteArray {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="RetroCam">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about=""
 xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
 xmlns:Container="http://ns.google.com/photos/1.0/container/"
 xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
 GCamera:MotionPhoto="1"
 GCamera:MotionPhotoVersion="1"
 GCamera:MotionPhotoPresentationTimestampUs="$presentationUs"
 GCamera:MicroVideo="1"
 GCamera:MicroVideoVersion="1"
 GCamera:MicroVideoOffset="${mp4.size}"
 GCamera:MicroVideoPresentationTimestampUs="$presentationUs">
<Container:Directory><rdf:Seq>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${mp4.size}" Item:Padding="0"/></rdf:li>
</rdf:Seq></Container:Directory>
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>"""
        val ns = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        val payload = ns + xmp.toByteArray(Charsets.UTF_8)
        val segLen = payload.size + 2
        require(segLen <= 0xFFFF) { "xmp too large" }
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a jpeg" }
        // insert after SOI and any APP0 (JFIF) segment
        var pos = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            val l = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            pos = 4 + l
        }
        val out = ByteArrayOutputStream(jpeg.size + payload.size + mp4.size + 8)
        out.write(jpeg, 0, pos)
        out.write(0xFF); out.write(0xE1)
        out.write(segLen shr 8); out.write(segLen and 0xFF)
        out.write(payload)
        out.write(jpeg, pos, jpeg.size - pos)
        out.write(mp4)
        return out.toByteArray()
    }
}
