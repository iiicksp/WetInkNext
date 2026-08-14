package com.wetinknext.data.export

import com.wetinknext.engine.export.ExportLayerSnapshot
import com.wetinknext.engine.export.ExportSnapshot
import java.io.ByteArrayOutputStream

/**
 * Minimal, dependency-free Adobe Photoshop document (PSD) writer.
 *
 * Writes an 8-bit RGB file: one layer record per editor layer (name,
 * visibility, RGBA channels), RLE (PackBits) compressed channel rows, and a
 * merged RGB composite of the visible layers. Layer opacity is already baked
 * into the captured pixels, so every record stores opacity 255 — Photoshop
 * flattens the file exactly like the editor composite.
 *
 * All multi-byte values are big-endian, per the PSD specification.
 */
object PsdWriter {

    private const val CHANNELS_PER_LAYER = 4

    fun write(snapshot: ExportSnapshot): ByteArray {
        require(snapshot.width > 0 && snapshot.height > 0) { "Cannot export an empty canvas" }
        val out = ByteArrayOutputStream()

        // ---- Header ----
        out.write("8BPS".toByteArray(Charsets.US_ASCII))
        writeU16(out, 1)                 // version
        out.write(ByteArray(6))          // reserved, must be zero
        writeU16(out, CHANNELS_PER_LAYER)
        writeU32(out, snapshot.height)
        writeU32(out, snapshot.width)
        writeU16(out, 8)                 // bits per channel
        writeU16(out, 3)                 // colour mode: RGB

        // ---- Colour mode data (none) and image resources (none) ----
        writeU32(out, 0)
        writeU32(out, 0)

        // ---- Layer and mask information ----
        // PSD stores the topmost layer first; the editor keeps layers bottom-first.
        val layerSection = buildLayerSection(snapshot, snapshot.layers.reversed())
        writeU32(out, layerSection.size)
        out.write(layerSection)

        // ---- Merged image data ----
        writeImageData(out, snapshot)

        return out.toByteArray()
    }

    private fun buildLayerSection(
        snapshot: ExportSnapshot,
        records: List<ExportLayerSnapshot>,
    ): ByteArray {
        val section = ByteArrayOutputStream()
        writeU16(section, records.size)

        for (layer in records) {
            require(layer.width == snapshot.width && layer.height == snapshot.height) {
                "PSD export requires layer snapshots at canvas size"
            }
            writeLayerRecord(section, layer, snapshot.width, snapshot.height)
        }

        // Layer mask / global layer mask info: none.
        writeU32(section, 0)
        return section.toByteArray()
    }

    private fun writeLayerRecord(
        section: ByteArrayOutputStream,
        layer: ExportLayerSnapshot,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        // Encode all channels first so their byte lengths are known before the
        // channel description block is written.
        val payloads = arrayOf(
            packChannel(canvasHeight, canvasWidth, layer.rgba, channel = 0),
            packChannel(canvasHeight, canvasWidth, layer.rgba, channel = 1),
            packChannel(canvasHeight, canvasWidth, layer.rgba, channel = 2),
            packChannel(canvasHeight, canvasWidth, layer.rgba, channel = 3),
        )

        // Bounding rectangle (top, left, bottom, right) in canvas pixels.
        writeU32(section, 0)
        writeU32(section, 0)
        writeU32(section, canvasHeight)
        writeU32(section, canvasWidth)

        writeU16(section, CHANNELS_PER_LAYER)
        val channelIds = intArrayOf(0, 1, 2, -1)
        for (index in channelIds.indices) {
            writeS16(section, channelIds[index])
            writeU32(section, payloads[index].byteLength)
        }

        section.write("norm".toByteArray(Charsets.US_ASCII)) // blend mode: normal
        section.write(255)                                   // opacity (baked into pixels)
        section.write(0)                                     // clipping: base
        section.write(if (layer.visible) 0x02 else 0x00)      // flags: bit 1 = visible
        section.write(0)                                      // filler

        // Extra data: layer mask (none), blending ranges (none), name.
        val extra = ByteArrayOutputStream()
        writeU32(extra, 0) // layer mask data
        writeU32(extra, 0) // blend ranges
        val nameBytes = layer.name.toByteArray(Charsets.UTF_16BE)
        val nameBlock = (nameBytes.size + 2 + 3) / 4 * 4 // UTF-16BE + terminator, padded to 4
        writeU32(extra, nameBlock)
        extra.write(nameBytes)
        extra.write(ByteArray(2))                      // UTF-16 null terminator
        extra.write(ByteArray(nameBlock - nameBytes.size - 2))
        writeU32(section, extra.size())
        section.write(extra.toByteArray())

        // Channel pixel data, in record order (R, G, B, alpha).
        for (payload in payloads) {
            writeU16(section, 1) // compression: RLE
            for (rowLength in payload.rowLengths) writeU16(section, rowLength)
            section.write(payload.data)
        }
    }

    private fun writeImageData(out: ByteArrayOutputStream, snapshot: ExportSnapshot) {
        writeU16(out, 1) // compression: RLE
        // The merged image carries RGB only (no alpha) for an RGB-mode file.
        for (channel in 0 until 3) {
            val payload = packChannel(snapshot.height, snapshot.width, snapshot.composite, channel)
            for (row in payload.rowLengths) writeU16(out, row)
            out.write(payload.data)
        }
    }

    private class ChannelPayload(val rowLengths: IntArray, val data: ByteArray) {
        val byteLength: Int get() = rowLengths.size * 2 + data.size
    }

    /** Splits a top-down RGBA8 buffer into one PackBits-encoded channel. */
    private fun packChannel(height: Int, width: Int, rgba: ByteArray, channel: Int): ChannelPayload {
        val rowLengths = IntArray(height)
        val payload = ByteArrayOutputStream(width * height / 2)
        for (row in 0 until height) {
            val rowStart = row * width * 4
            val rowBytes = ByteArray(width)
            for (x in 0 until width) {
                rowBytes[x] = rgba[rowStart + x * 4 + channel]
            }
            val encoded = packBits(rowBytes)
            rowLengths[row] = encoded.size
            payload.write(encoded)
        }
        return ChannelPayload(rowLengths, payload.toByteArray())
    }

    /** Classic PackBits: runs of 2..128 identical bytes, literals up to 128. */
    private fun packBits(row: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(row.size + (row.size / 128) + 2)
        var index = 0
        val size = row.size
        while (index < size) {
            var run = 1
            while (index + run < size && run < 128 && row[index + run] == row[index]) run++
            if (run >= 2) {
                out.write(1 - run) // -1..-127 encodes a run of 2..128
                out.write(row[index].toInt())
                index += run
            } else {
                var end = index
                while (end < size && (end - index) < 128) {
                    var repeat = 1
                    while (end + repeat < size && repeat < 128 && row[end + repeat] == row[end]) repeat++
                    if (repeat >= 2) break
                    end++
                }
                val literalLength = end - index
                out.write(literalLength - 1) // 0..127 encodes a literal of 1..128
                out.write(row, index, literalLength)
                index = end
            }
        }
        return out.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeS16(out: ByteArrayOutputStream, value: Int) = writeU16(out, value and 0xFFFF)

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}