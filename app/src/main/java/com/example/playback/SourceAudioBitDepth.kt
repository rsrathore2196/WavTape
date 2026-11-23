package com.example.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.FlacStreamMetadata

/** Read precision from the input format or its codec configuration, never from decoder output. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun Format.sourceBitDepth(): Int {
    when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> return 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> return 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> return 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> return 32
    }
    // MP4/DASH demuxers can leave pcmEncoding unset for FLAC and ALAC, even
    // though the source bit depth is present in the decoder initialization bytes.
    val config = initializationData.firstOrNull() ?: return 0
    return when (sampleMimeType) {
        MimeTypes.AUDIO_FLAC -> {
            // fLaC magic followed by a 34-byte STREAMINFO metadata block.
            if (config.size < 42 || String(config, 0, 4, Charsets.US_ASCII) != "fLaC" ||
                config[4].toInt() and 0x7f != 0 || config[5] != 0.toByte() ||
                config[6] != 0.toByte() || config[7] != 34.toByte()) return 0
            val info = FlacStreamMetadata(config, 8)
            info.bitsPerSample.takeIf { info.sampleRate > 0 && it in 4..32 } ?: 0
        }
        MimeTypes.AUDIO_ALAC -> {
            // ALACSpecificConfig: frame length, compatibility version, then bit depth.
            if (config.size < 24 || config[4] != 0.toByte()) return 0
            val depth = config[5].toInt() and 0xff
            if (depth != 16 && depth != 20 && depth != 24 && depth != 32) return 0
            val properties = runCatching { CodecSpecificDataUtil.parseAlacAudioSpecificConfig(config) }.getOrNull()
            if (properties != null && properties.first > 0 && properties.second in 1..8) depth else 0
        }
        else -> 0
    }
}
