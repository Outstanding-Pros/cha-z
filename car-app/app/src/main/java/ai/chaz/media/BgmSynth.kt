package ai.chaz.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * 릴스 배경 음악 분위기. 음원 파일 없이 앱이 직접 합성한다 → 차~즈 자체 제작 음원(저작권 문제 없음).
 * bpm 은 기획서 비트 규칙(이어붙이기 2초 = BPM 120 의 4박, 차박 = BPM 80).
 */
enum class BgmMood(val label: String, val bpm: Int) {
    BRIGHT("산뜻한 드라이브", 120),
    OCEAN("바다가 열리는 순간", 120),
    NIGHT("잔잔한 밤", 80),
    TRAILER("예고편", 90),
    PLAYFUL("장난스러운", 120),
}

/** 모노 16bit PCM 합성기. 순수 계산이라 단위 테스트한다. */
object BgmSynth {
    const val RATE = 44_100

    // 진행: I–V–vi–IV (C 장조) / 밤: vi–IV–I–V / 예고편: i–VI (A 단조)
    private val POP = listOf(chord(261.63, 329.63, 392.0), chord(196.0, 246.94, 293.66), chord(220.0, 261.63, 329.63), chord(174.61, 220.0, 261.63))
    private val NIGHT = listOf(chord(220.0, 261.63, 329.63), chord(174.61, 220.0, 261.63), chord(261.63, 329.63, 392.0), chord(196.0, 246.94, 293.66))
    private val DARK = listOf(chord(110.0, 130.81, 164.81), chord(87.31, 110.0, 130.81))
    private fun chord(vararg f: Double) = f.toList()

    fun render(mood: BgmMood, durationMs: Long): ShortArray {
        val n = (durationMs * RATE / 1000).toInt()
        val buf = FloatArray(n)
        val beat = 60.0 / mood.bpm
        val bar = beat * 4
        val prog = when (mood) { BgmMood.NIGHT -> NIGHT; BgmMood.TRAILER -> DARK; else -> POP }
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val barIdx = (t / bar).toInt()
            val ch = prog[barIdx % prog.size]
            val inBeat = t % beat
            val beatIdx = (t / beat).toInt()
            var v = 0.0
            when (mood) {
                BgmMood.BRIGHT, BgmMood.OCEAN, BgmMood.PLAYFUL -> {
                    // 패드(삼화음) + 베이스 + 킥(1·3박) + 하이햇(엇박) + 8분 아르페지오
                    v += pad(ch, t, 0.10)
                    v += 0.22 * sin(2 * PI * ch[0] / 2 * t) * env(inBeat, 0.02, 0.5)
                    if (beatIdx % 2 == 0) v += kick(inBeat)
                    v += hat(i, (t + beat / 2) % beat) * 0.6
                    val eighth = (t / (beat / 2)).toInt()
                    val note = ch[eighth % 3] * (if (mood == BgmMood.PLAYFUL) 4 else 2)
                    v += 0.07 * tri(note * t) * env(t % (beat / 2), 0.005, 0.18)
                    if (mood == BgmMood.OCEAN) v += 0.05 * noise(i) * (0.5 + 0.5 * sin(2 * PI * t / (bar * 2))) // 파도 소리
                }
                BgmMood.NIGHT -> {
                    v += pad(ch, t, 0.14)
                    val q = (t / beat).toInt()
                    v += 0.08 * sin(2 * PI * ch[q % 3] * 2 * t) * env(inBeat, 0.01, 0.9)
                }
                BgmMood.TRAILER -> {
                    v += 0.18 * sin(2 * PI * 55.0 * t) + 0.08 * sin(2 * PI * 110.0 * t + sin(t))
                    v += pad(ch, t, 0.08)
                    val barT = t % (bar * 2)
                    if (barT < 0.6) v += 1.4 * kick(barT) + 0.25 * noise(i) * exp(-barT * 8) // 큰 타격
                    v += 0.05 * sin(2 * PI * (220.0 + 40 * (t % bar)) * t) * (t % bar / bar) // 상승음
                }
            }
            buf[i] = v.toFloat()
        }
        // 페이드 인·아웃, 정규화
        val fadeIn = RATE * 3 / 10; val fadeOut = RATE * 8 / 10
        for (i in 0 until n) {
            val g = minOf(1f, i.toFloat() / fadeIn, (n - i).toFloat() / fadeOut).coerceAtLeast(0f)
            buf[i] *= g
        }
        val peak = buf.maxOfOrNull { abs(it) }?.takeIf { it > 0f } ?: 1f
        val k = 0.8f / peak * Short.MAX_VALUE
        return ShortArray(n) { (buf[it] * k).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun pad(ch: List<Double>, t: Double, a: Double) = ch.sumOf { a * sin(2 * PI * it * t) * (0.8 + 0.2 * sin(2 * PI * 0.5 * t)) }
    private fun env(x: Double, att: Double, dec: Double) = if (x < att) x / att else exp(-(x - att) / dec * 3)
    private fun kick(x: Double): Double {
        if (x > 0.25) return 0.0
        val f = 50 + 70 * exp(-x * 30)
        return 0.55 * sin(2 * PI * f * x) * exp(-x * 14)
    }
    private fun hat(i: Int, x: Double) = if (x > 0.05) 0.0 else 0.06 * noise(i) * exp(-x * 80)
    private fun tri(x: Double): Double { val f = x - Math.floor(x); return 4 * abs(f - 0.5) - 1 }
    /** 결정적 잡음 (같은 입력 → 같은 소리). */
    private fun noise(i: Int): Double { var h = i * 374761393 + 668265263; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xFFFF) / 32768.0 - 1.0 }
}
