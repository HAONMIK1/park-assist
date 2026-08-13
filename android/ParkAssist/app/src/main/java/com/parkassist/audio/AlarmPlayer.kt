package com.parkassist.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.parkassist.protocol.AlarmTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 앱 경고음.
 *
 * [AlarmCurve][com.parkassist.protocol.AlarmCurve]가 계산한 강도대로 사인파를 합성해
 * 낸다. 미리 만든 음원을 재생하지 않는 이유는 음높이·길이·음량을 거리에 따라 **연속으로**
 * 바꾸기 위해서다.
 *
 * ## 두 가지 재생 방식
 *
 * | | 사용처 | 오디오 용도 | 오디오 포커스 |
 * | --- | --- | --- | --- |
 * | 일반 | 주차 | `ASSISTANCE_SONIFICATION` | 뺏지 않음 |
 * | 긴급 | 주행 중 급접근 | `ALARM` | `TRANSIENT_MAY_DUCK` |
 *
 * 긴급일 때 포커스를 가져가는 이유는 **티맵 같은 내비게이션 음성 위로 들려야** 하기
 * 때문이다. 상대 앱을 멈추지 않고 잠깐 줄이기만 한다.
 *
 * **이건 보조 수단이다.** 주 경보는 기기의 부저이고, 폰이 없어도 울린다(ADR 0001, 0003).
 */
class AlarmPlayer(
    context: Context,
    private val scope: CoroutineScope,
) {
    private data class Request(val tone: AlarmTone, val urgent: Boolean)

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val request = MutableStateFlow<Request?>(null)
    private var job: Job? = null

    private var focusRequest: AudioFocusRequest? = null

    /**
     * @param tone null이면 소리를 멈춘다.
     * @param urgent 내비게이션 음성을 눌러서라도 들려야 하는 경보인지.
     */
    fun setTone(tone: AlarmTone?, urgent: Boolean = false) {
        request.value = tone?.let { Request(it, urgent) }
        if (tone != null && job?.isActive != true) startLoop()
    }

    fun release() {
        request.value = null
        job?.cancel()
        job = null
        abandonFocus()
    }

    private fun startLoop() {
        job = scope.launch(Dispatchers.Default) {
            val silence = ShortArray(SAMPLE_RATE * GAP_CHUNK_MS.toInt() / 1000)
            var track: AudioTrack? = null
            var trackIsUrgent: Boolean? = null

            try {
                while (isActive) {
                    val current = request.value
                    if (current == null) {
                        abandonFocus()
                        track?.let { runCatching { it.pause(); it.flush() } }
                        request.first { it != null } // 다음 경보까지 대기 — CPU를 쓰지 않는다
                        continue
                    }

                    // 긴급/일반은 오디오 용도가 달라서 트랙을 다시 만들어야 한다.
                    if (track == null || trackIsUrgent != current.urgent) {
                        track?.let { runCatching { it.stop(); it.release() } }
                        track = runCatching { buildTrack(current.urgent) }.getOrNull() ?: return@launch
                        trackIsUrgent = current.urgent
                        runCatching { track.play() }
                    }

                    if (current.urgent) requestFocus() else abandonFocus()

                    val active = track ?: return@launch
                    active.setVolume(current.tone.volume.coerceIn(0f, 1f))
                    writeBeep(active, current.tone)
                    if (!current.tone.continuous) writeGap(active, silence, current.tone)
                }
            } finally {
                abandonFocus()
                track?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
            }
        }
    }

    /** 사인파 한 번. 딸깍 소리를 막으려고 앞뒤에 짧은 페이드를 준다. */
    private fun writeBeep(track: AudioTrack, tone: AlarmTone) {
        val freq = BASE_FREQ_HZ * tone.pitchRatio
        val count = (SAMPLE_RATE * tone.beepMs / 1000L).toInt().coerceAtLeast(MIN_SAMPLES)

        // 연속음일 때 페이드를 넣으면 이어 붙인 자리마다 음량이 출렁인다.
        val fade = if (tone.continuous) 0 else min(count / 8, SAMPLE_RATE * FADE_MS / 1000)

        val buffer = ShortArray(count)
        for (i in 0 until count) {
            val envelope = when {
                fade == 0 -> 1f
                i < fade -> i.toFloat() / fade
                i >= count - fade -> (count - i).toFloat() / fade
                else -> 1f
            }
            val sample = sin(2.0 * PI * freq * i / SAMPLE_RATE)
            buffer[i] = (sample * envelope * Short.MAX_VALUE * HEADROOM).toInt().toShort()
        }
        track.write(buffer, 0, count)
    }

    /**
     * 다음 소리까지의 침묵.
     *
     * 한 번에 다 쓰지 않고 조각내서 쓴다. 그래야 중간에 물체가 더 가까워졌을 때
     * 남은 침묵을 버리고 즉시 다음 소리로 넘어갈 수 있다.
     */
    private suspend fun writeGap(track: AudioTrack, silence: ShortArray, tone: AlarmTone) {
        var remainingMs = (tone.intervalMs - tone.beepMs).coerceAtLeast(0L)

        while (remainingMs > 0 && coroutineContext.isActive) {
            val chunkMs = min(remainingMs, GAP_CHUNK_MS)
            val count = (SAMPLE_RATE * chunkMs / 1000L).toInt().coerceAtMost(silence.size)
            if (count <= 0) return
            track.write(silence, 0, count)
            remainingMs -= chunkMs

            val latest = request.value ?: return
            if (latest.tone.intervalMs < tone.intervalMs) return // 더 급해졌다 → 즉시 다음 소리
        }
    }

    private fun buildTrack(urgent: Boolean): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(attributes(urgent))
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun attributes(urgent: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(
            // 주행 중 급접근은 알람 볼륨으로 낸다. 사용자가 음악 볼륨을 줄여 놨어도 들려야 한다.
            if (urgent) AudioAttributes.USAGE_ALARM
            else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** 내비게이션·음악을 잠깐 줄이고 그 위로 낸다. 멈추게 하지는 않는다. */
    private fun requestFocus() {
        if (focusRequest != null) return
        val manager = audioManager ?: return

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes(urgent = true))
            .setWillPauseWhenDucked(false)
            .build()

        if (manager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = req
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private companion object {
        const val SAMPLE_RATE = 44_100

        /**
         * 기준 주파수.
         *
         * 대상 사용자가 60대다. 고음역 청력이 먼저 떨어지므로 자동차 주차 센서가 흔히
         * 쓰는 2.5~3kHz보다 낮게 잡았다. 최대 음높이(×1.45)에서도 2.6kHz를 넘지 않는다.
         */
        const val BASE_FREQ_HZ = 1_800.0

        const val HEADROOM = 0.9
        const val FADE_MS = 4
        const val MIN_SAMPLES = 64
        const val GAP_CHUNK_MS = 20L
    }
}
