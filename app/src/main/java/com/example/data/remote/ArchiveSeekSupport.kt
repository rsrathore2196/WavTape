package com.example.data.remote

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import java.util.concurrent.CopyOnWriteArrayList

/** Updates the progressive timeline through ExtractorOutput, without replacing the playing item. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ArchiveSeekSupport(private val rangeSupported: () -> Boolean, private val fullyCached: () -> Boolean) {
    private val active = CopyOnWriteArrayList<ArchiveExtractor>()

    fun dataSources(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val delegate = factory.createDataSource()
        object : DataSource by delegate {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                delegate.read(buffer, offset, length).also { active.forEach { it.refreshAfterRead() } }

            override fun close() {
                try {
                    delegate.close()
                } finally {
                    // Media3 commits the last cache span during close, after the extractor sees EOF.
                    active.forEach { it.refresh() }
                }
            }
        }
    }

    fun extractors(): ExtractorsFactory = object : ExtractorsFactory {
        private val delegate = DefaultExtractorsFactory()

        override fun createExtractors(): Array<Extractor> =
            delegate.createExtractors().map(::wrap).toTypedArray()

        override fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()
    }

    internal fun wrap(extractor: Extractor): Extractor = ArchiveExtractor(extractor)

    private inner class ArchiveExtractor(private val extractor: Extractor) : Extractor by extractor {
        private lateinit var output: ExtractorOutput
        private var map: SeekMap? = null
        private var emitted: SeekMap? = null
        private var unseekable: SeekMap? = null
        private var input: ExtractorInput? = null
        private var frames: FlacFrames? = null
        private var finishedMap: SeekMap? = null
        private var lastRangeSupport = false

        override fun init(output: ExtractorOutput) {
            this.output = output
            active.add(this)
            extractor.init(object : ExtractorOutput by output {
                override fun seekMap(seekMap: SeekMap) {
                    map = seekMap
                    unseekable = SeekMap.Unseekable(seekMap.durationUs)
                    if (extractor is FlacExtractor && !seekMap.isSeekable && seekMap.durationUs > 0) {
                        frames = input?.let { FlacFrames(it.position, seekMap.durationUs) }
                    }
                    refresh()
                }

                override fun track(id: Int, type: Int): TrackOutput {
                    val delegate = output.track(id, type)
                    if (extractor !is FlacExtractor || type != C.TRACK_TYPE_AUDIO) return delegate
                    return object : TrackOutput by delegate {
                        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int,
                            cryptoData: TrackOutput.CryptoData?) {
                            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                            frames?.sample(timeUs, size, offset)
                        }
                    }
                }
            })
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            this.input = input
            try {
                val result = extractor.read(input, seekPosition)
                if (result == Extractor.RESULT_END_OF_INPUT) {
                    frames?.complete(input.position)?.let { finishedMap = it }
                    frames = null
                }
                return result
            } finally {
                this.input = null
            }
        }

        fun refreshAfterRead() {
            // A cache hit can precede the first HTTP response. Refresh when that response
            // establishes or withdraws range support. Full-cache checks stay off the read loop.
            if (map?.isSeekable == true && lastRangeSupport != rangeSupported()) refresh()
        }

        fun refresh() {
            val native = map ?: return
            val ranges = rangeSupported()
            lastRangeSupport = ranges
            val available = when {
                native.isSeekable && (ranges || fullyCached()) -> native
                finishedMap != null && fullyCached() -> finishedMap
                else -> unseekable
            } ?: return
            if (available !== emitted) {
                emitted = available
                output.seekMap(available)
            }
        }

        override fun seek(position: Long, timeUs: Long) {
            frames = null
            extractor.seek(position, timeUs)
        }

        override fun release() {
            active.remove(this)
            extractor.release()
        }
    }

    /** FLAC’s native binary seek map needs a length on the first read. For an unknown-length
     * stream, retain a bounded index of exact frame offsets emitted by the real extractor. */
    private class FlacFrames(start: Long, private val durationUs: Long) {
        private val stepUs = maxOf(250_000L, durationUs / 2_048 + 1)
        private val points = ArrayList<SeekPoint>()
        private var position = start
        private var previousTime = -1L
        private var valid = true

        fun sample(timeUs: Long, size: Int, offset: Int) {
            if (!valid) return
            if (offset != 0 || size <= 0 || timeUs <= previousTime || timeUs >= durationUs ||
                previousTime < 0 && timeUs != 0L) {
                valid = false
                return
            }
            if (points.isEmpty() || timeUs - points.last().timeUs >= stepUs) points += SeekPoint(timeUs, position)
            previousTime = timeUs
            position += size
        }

        fun complete(end: Long): SeekMap? {
            if (!valid || position != end || points.size < 2) return null
            val index = points.toList()
            return object : SeekMap {
                override fun isSeekable() = true
                override fun getDurationUs(): Long = this@FlacFrames.durationUs
                override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                    val found = index.binarySearchBy(timeUs) { it.timeUs }
                    val floor = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
                    val first = index[floor]
                    return if (floor == index.lastIndex || first.timeUs >= timeUs) SeekMap.SeekPoints(first)
                    else SeekMap.SeekPoints(first, index[floor + 1])
                }
            }
        }
    }
}
