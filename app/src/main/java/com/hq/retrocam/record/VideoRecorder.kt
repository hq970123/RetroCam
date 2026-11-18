package com.hq.retrocam.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 (surface input) + AAC recorder writing an MP4. The render thread draws into
 * [inputSurface] with presentation times from System.nanoTime().
 */
class VideoRecorder(
    private val output: File,
    width: Int,
    height: Int,
    bitrate: Int,
    fps: Int,
    orientationHint: Int,
    private val withAudio: Boolean,
    mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    /** Peak levels per channel (0..1), called on the audio thread ~20 times a second. */
    private val onLevel: ((Float, Float) -> Unit)? = null,
    private val onFinished: (File?, Throwable?) -> Unit,
) {
    private val tag = "RetroCam.Rec"
    private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val videoCodec: MediaCodec
    val inputSurface: Surface
    private val codecThread = HandlerThread("rec-codec").apply { start() }
    private val codecHandler = Handler(codecThread.looper)

    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)

    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var videoDone = false
    private var audioDone = true
    private var finished = false
    private var failure: Throwable? = null
    private val pending = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()

    init {
        muxer.setOrientationHint(orientationHint)
        val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoCodec = MediaCodec.createEncoderByType(mime)
        videoCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        writeSample(true, buf, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        synchronized(lock) { videoDone = true }
                        maybeFinish()
                    }
                } catch (t: Throwable) {
                    fail(t)
                }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail(e)
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                synchronized(lock) {
                    videoTrack = muxer.addTrack(format)
                    tryStartMuxer()
                }
            }
        }, codecHandler)
        videoCodec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoCodec.createInputSurface()
    }

    fun start() {
        videoCodec.start()
        if (withAudio) {
            try { startAudio() } catch (t: Throwable) {
                Log.w(tag, "audio disabled", t)
                audioCodec = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val rate = 48000
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val channels = 2
        val minBuf = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec
        audioRecord = rec
        synchronized(lock) { audioDone = false }
        audioRunning.set(true)
        rec.startRecording()
        audioThread = Thread({ audioLoop(rec, codec, rate, channels) }, "rec-audio").apply { start() }
    }

    private fun audioLoop(rec: AudioRecord, codec: MediaCodec, rate: Int, channels: Int) {
        val startUs = System.nanoTime() / 1000
        var frames = 0L
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * channels
        var eosQueued = false
        try {
            while (true) {
                if (!eosQueued) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        inBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val stop = !audioRunning.get()
                        val read = if (stop) 0 else rec.read(inBuf, minOf(inBuf.capacity(), 4096))
                        val pts = startUs + frames * 1_000_000L / rate
                        if (stop) {
                            codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else if (read > 0) {
                            if (onLevel != null) {
                                var pl = 0; var pr = 0
                                var i = 0
                                val step = 2 * channels
                                while (i + step <= read) {
                                    val l = kotlin.math.abs(inBuf.getShort(i).toInt())
                                    val r = if (channels > 1) kotlin.math.abs(inBuf.getShort(i + 2).toInt()) else l
                                    if (l > pl) pl = l
                                    if (r > pr) pr = r
                                    i += step
                                }
                                onLevel.invoke(pl / 32768f, pr / 32768f)
                            }
                            codec.queueInputBuffer(inIdx, 0, read, pts, 0)
                            frames += read / bytesPerFrame
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, pts, 0)
                        }
                    }
                }
                var outIdx = codec.dequeueOutputBuffer(info, if (eosQueued) 10_000 else 0)
                while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(lock) {
                            audioTrack = muxer.addTrack(codec.outputFormat)
                            tryStartMuxer()
                        }
                    } else if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            writeSample(false, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            synchronized(lock) { audioDone = true }
                            maybeFinish()
                            return
                        }
                    }
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "audio loop failed", t)
            synchronized(lock) { audioDone = true }
            maybeFinish()
        }
    }

    private fun tryStartMuxer() {
        val audioReady = audioCodec == null || audioTrack >= 0
        if (!muxerStarted && videoTrack >= 0 && audioReady) {
            muxer.start()
            muxerStarted = true
            for ((kind, buf, info) in pending) {
                val track = if (kind == -1) videoTrack else audioTrack
                if (track >= 0) muxer.writeSampleData(track, buf, info)
            }
            pending.clear()
        }
    }

    private fun writeSample(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (finished) return
            val track = if (video) videoTrack else audioTrack
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            if (muxerStarted && track >= 0) {
                muxer.writeSampleData(track, buf, info)
            } else if (pending.size < 600) {
                val copy = ByteBuffer.allocateDirect(info.size)
                copy.put(buf)
                copy.flip()
                val ci = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                // track index resolved at flush time
                pending.add(Triple(if (video) -1 else -2, copy, ci))
            }
        }
    }

    /** Signals end of stream; [onFinished] fires on a background thread when the file is complete. */
    fun stop() {
        audioRunning.set(false)
        try { videoCodec.signalEndOfInputStream() } catch (t: Throwable) { fail(t) }
        codecHandler.postDelayed({ // safety net in case EOS never arrives
            synchronized(lock) { videoDone = true; audioDone = true }
            maybeFinish()
        }, 4000)
    }

    private fun fail(t: Throwable) {
        Log.e(tag, "recorder failure", t)
        synchronized(lock) { failure = failure ?: t; videoDone = true }
        audioRunning.set(false)
        maybeFinish()
    }

    private fun maybeFinish() {
        synchronized(lock) {
            if (finished || !videoDone || !audioDone) return
            finished = true
        }
        var err = failure
        try { videoCodec.stop() } catch (_: Throwable) {}
        try { videoCodec.release() } catch (_: Throwable) {}
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        try { audioCodec?.stop() } catch (_: Throwable) {}
        try { audioCodec?.release() } catch (_: Throwable) {}
        try {
            if (muxerStarted) muxer.stop() else err = err ?: IllegalStateException("no frames recorded")
        } catch (t: Throwable) { err = err ?: t }
        try { muxer.release() } catch (_: Throwable) {}
        inputSurface.release()
        codecThread.quitSafely()
        onFinished(if (err == null) output else null, err)
    }
}
