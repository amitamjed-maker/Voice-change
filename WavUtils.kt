package com.belawal.voicechanger

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV (PCM 16-bit, mono) read/write helper.
 * Kept dependency-free so it builds cleanly from a GitHub Actions runner.
 */
object WavUtils {

    data class WavData(val samples: ShortArray, val sampleRate: Int)

    fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = samples.size * 2
        val chunkSize = 36 + dataSize

        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(chunkSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16) // PCM header size
            header.putShort(1) // PCM format
            header.putShort(numChannels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)
            out.write(header.array())

            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) body.putShort(s)
            out.write(body.array())
        }
    }

    fun readWav(file: File): WavData {
        FileInputStream(file).use { input ->
            val header = ByteArray(44)
            var read = 0
            while (read < 44) {
                val r = input.read(header, read, 44 - read)
                if (r < 0) break
                read += r
            }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(24)
            val sampleRate = bb.int
            bb.position(40)
            val dataSize = bb.int

            val dataBytes = ByteArray(dataSize)
            var offset = 0
            while (offset < dataSize) {
                val r = input.read(dataBytes, offset, dataSize - offset)
                if (r < 0) break
                offset += r
            }
            val db = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray(dataSize / 2)
            for (i in samples.indices) samples[i] = db.short
            return WavData(samples, sampleRate)
        }
    }

    /** Wraps a raw (headerless) 16-bit mono PCM file into a proper WAV file. */
    fun pcmFileToWav(pcmFile: File, wavFile: File, sampleRate: Int) {
        val bytes = pcmFile.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(bytes.size / 2)
        for (i in samples.indices) samples[i] = bb.short
        writeWav(wavFile, samples, sampleRate)
    }
}
