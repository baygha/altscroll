package com.altscroll.app.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.io.File

/**
 * Hold-to-talk recorder.
 *
 * AAC in an MPEG-4 container, because Whisper accepts m4a directly and it is
 * roughly a tenth the size of WAV — the clip is base64'd into a JSON body, so
 * bytes on the wire matter.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return false

        val file = File.createTempFile("voice_", ".m4a", context.cacheDir)
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return runCatching {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // Speech, not music. Whisper gains nothing from a higher rate
                // and the upload gets slower.
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            outputFile = file
            startedAt = System.currentTimeMillis()
            true
        }.getOrElse {
            runCatching { rec.release() }
            file.delete()
            false
        }
    }

    /**
     * Stops and returns the clip as base64, or null if the recording failed or
     * was too short to contain speech. The temp file is always cleaned up.
     */
    fun stopAndEncode(): String? {
        val rec = recorder ?: return null
        val file = outputFile
        val heldMs = System.currentTimeMillis() - startedAt
        recorder = null
        outputFile = null

        // MediaRecorder.stop() throws if it never captured a frame, which is
        // what a quick accidental tap produces.
        val stopped = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }

        if (!stopped || file == null || heldMs < 500) {
            file?.delete()
            return null
        }

        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        if (bytes == null || bytes.isEmpty()) return null

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Abandon the recording without producing anything. */
    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        outputFile?.delete()
        outputFile = null
    }
}
