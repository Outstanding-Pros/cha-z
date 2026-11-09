package ai.chaz.voice

/** SPEC '음성 인터페이스 > 인텐트'. */
sealed interface Intent {
    data object CaptureDefault : Intent
    /** 둘 중 하나만 말해도 된다. 비어 있는 값은 실행할 때 채운다. */
    data class CaptureCustom(val durationSec: Int?, val count: Int?) : Intent
    data object Retake : Intent
    data object Stop : Intent
    data class BgmPlay(val mood: String?) : Intent
    data object BgmStop : Intent
    data object HandoffPhone : Intent
    data object SendPhone : Intent
    data class LightMood(val preset: String?) : Intent
    data object Help : Intent
    data object Unknown : Intent
}

/** wake = 호출어("차즈")가 들어 있었는지, command = 호출어를 뺀 나머지(정규화됨). */
data class Parsed(val intent: Intent, val wake: Boolean, val command: String)

/**
 * 규칙 파서 (순수 함수). 맞는 규칙이 없으면 Intent.Unknown → 호출 측이 LLM 폴백.
 * 1. 정규화: 공백·물결표·문장부호 제거, 호출어 변형을 "차즈"로 통일 ("치즈"는 받지 않는다)
 * 2. 한글 숫자 → 숫자 (단위 바로 앞의 수사만 바꾼다: "사진"의 '사'를 4로 읽지 않게)
 * 3. 시간 (\d+)(초|분), 4. 횟수 (\d+)(번|컷|장)
 * 5. 키워드로 나머지 인텐트
 */
object IntentParser {

    const val WAKE = "차즈"
    /** STT가 내놓을 수 있는 호출어 표기. 정규화(공백 제거) 뒤에 비교한다. 긴 것부터. */
    private val WAKE_VARIANTS = listOf("차즈야", "챠즈야", "차즈", "챠즈", "차쯔", "챠쯔")

    private const val NATIVE_UNITS = "하나|한|둘|두|셋|세|넷|네|다섯|여섯|일곱|여덟|아홉"
    private const val SINO = "[일이삼사오육칠팔구]"
    private val NUMBER = "(\\d+|(?:스물|스무|열)?(?:$NATIVE_UNITS)|스물|스무|열|$SINO?십$SINO?|$SINO)"
    private val TIME = Regex("$NUMBER(초|분)")
    private val COUNT = Regex("$NUMBER(번|컷|장)")

    private val MOODS = listOf("신나는", "잔잔한", "감성", "드라이브")
    private val PRESETS = listOf("코지", "골든아워", "시티나이트", "오션", "포레스트")

    fun parse(text: String): Parsed {
        val norm = normalize(text)
        val wake = norm.contains(WAKE)
        val cmd = norm.replace(WAKE, "")
        return Parsed(classify(cmd), wake, cmd)
    }

    fun normalize(text: String): String {
        var s = text.replace(Regex("[\\s~～∼\\-.,!?！？。、]"), "")
        for (v in WAKE_VARIANTS) s = s.replace(v, WAKE)
        return s
    }

    private fun classify(cmd: String): Intent {
        fun has(vararg k: String) = k.any { cmd.contains(it) }

        if (has("노래", "음악", "브금", "BGM", "bgm")) {
            return if (has("꺼", "끄", "그만", "멈춰", "중지", "정지")) Intent.BgmStop
            else Intent.BgmPlay(MOODS.firstOrNull { cmd.contains(it) })
        }
        if (has("스마트폰", "휴대폰", "핸드폰", "폰")) {
            return if (has("편집")) Intent.HandoffPhone else Intent.SendPhone
        }
        if (has("분위기", "조명")) return Intent.LightMood(PRESETS.firstOrNull { cmd.contains(it) })
        if (has("뭐할수있", "뭘할수있", "도움말", "도와줘", "명령어")) return Intent.Help
        if (has("그만", "멈춰", "중지", "스톱", "정지")) return Intent.Stop
        if (has("다시", "한번더", "한장더")) return Intent.Retake

        val duration = TIME.find(cmd)?.let { m -> number(m.groupValues[1])?.let { if (m.groupValues[2] == "분") it * 60 else it } }
        val count = COUNT.find(cmd)?.let { number(it.groupValues[1]) }
        if (duration != null || count != null) return Intent.CaptureCustom(duration, count)

        // 호출어만 있거나 "찍어줘/사진/촬영"만 있으면 기본 촬영
        if (cmd.isEmpty() || cmd.matches(Regex("(사진|촬영)?(찍어|찍자|찍어줘|찍어주세요|해줘|해|시작)?(줘|주세요|요)?"))) return Intent.CaptureDefault
        if (has("찍어", "찍자", "촬영")) return Intent.CaptureDefault
        return Intent.Unknown
    }

    /** "10" / "열" / "다섯" / "스무" / "삼십" / "십오" → Int. */
    fun number(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val native = mapOf(
            "하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "넷" to 4, "네" to 4,
            "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9,
        )
        val sino = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)
        native[s]?.let { return it }
        for ((prefix, tens) in listOf("스물" to 20, "스무" to 20, "열" to 10)) {
            if (s == prefix) return tens
            if (s.startsWith(prefix)) native[s.removePrefix(prefix)]?.let { return tens + it }
        }
        if (s.contains('십')) {
            val (a, b) = s.split('십', limit = 2)
            val tens = if (a.isEmpty()) 1 else sino[a.single()] ?: return null
            val ones = if (b.isEmpty()) 0 else sino[b.single()] ?: return null
            return tens * 10 + ones
        }
        if (s.length == 1) sino[s[0]]?.let { return it }
        return null
    }
}
