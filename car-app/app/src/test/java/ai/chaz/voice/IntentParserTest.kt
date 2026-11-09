package ai.chaz.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC 인텐트 표의 발화 예 전부 + 경계 사례. */
class IntentParserTest {

    private fun intent(text: String) = IntentParser.parse(text).intent

    @Test fun `SPEC 표 발화 예`() {
        val cases = mapOf(
            "차~즈" to Intent.CaptureDefault,
            "차즈 1분 동안 10번 찍어줘" to Intent.CaptureCustom(60, 10),
            "차즈 30초 동안 다섯 장" to Intent.CaptureCustom(30, 5),
            "차즈 다시 찍어줘" to Intent.Retake,
            "차즈 그만" to Intent.Stop,
            "차즈 멈춰" to Intent.Stop,
            "차즈 노래 틀어줘" to Intent.BgmPlay(null),
            "차즈 잔잔한 노래" to Intent.BgmPlay("잔잔한"),
            "차즈 신나는 노래" to Intent.BgmPlay("신나는"),
            "차즈 노래 꺼줘" to Intent.BgmStop,
            "차즈 스마트폰에서 편집할게" to Intent.HandoffPhone,
            "차즈 폰으로 보내줘" to Intent.SendPhone,
            "차즈 분위기 바꿔줘" to Intent.LightMood(null),
            "차즈 뭐 할 수 있어?" to Intent.Help,
            "차즈 30초 동안 6번 찍어줘" to Intent.CaptureCustom(30, 6), // 시연 콘티 6번
        )
        cases.forEach { (text, expected) -> assertEquals(text, expected, intent(text)) }
    }

    @Test fun `호출어 변형은 받고 치즈는 받지 않는다`() {
        listOf("차즈", "차즈야", "차 즈", "차~즈!", "챠즈", "차즈.").forEach {
            assertTrue(it, IntentParser.parse(it).wake)
        }
        assertFalse(IntentParser.parse("치즈").wake)
        assertFalse(IntentParser.parse("치즈 1분 동안 10번").wake)
    }

    @Test fun `호출어만 있으면 기본 촬영, 명령은 비어 있음`() {
        val p = IntentParser.parse("차즈야")
        assertEquals(Intent.CaptureDefault, p.intent)
        assertEquals("", p.command)
    }

    @Test fun `한글 숫자`() {
        assertEquals(Intent.CaptureCustom(60, 10), intent("차즈 일 분 동안 열 번"))
        assertEquals(Intent.CaptureCustom(30, 20), intent("차즈 삼십 초 동안 스무 장"))
        assertEquals(Intent.CaptureCustom(15, 3), intent("차즈 십오초 동안 세 컷"))
        assertEquals(Intent.CaptureCustom(120, 12), intent("차즈 2분 동안 열두 번"))
        assertEquals(Intent.CaptureCustom(null, 8), intent("차즈 여덟 장 찍어줘"))
        assertEquals(Intent.CaptureCustom(20, null), intent("차즈 20초 동안 찍어줘"))
        // '사진'의 '사'를 숫자로 읽지 않는다
        assertEquals(Intent.CaptureDefault, intent("차즈 사진 찍어줘"))
    }

    @Test fun `숫자 변환`() {
        mapOf("10" to 10, "열" to 10, "다섯" to 5, "스무" to 20, "스물하나" to 21, "열한" to 11,
            "삼십" to 30, "십오" to 15, "십" to 10, "일" to 1, "이십오" to 25)
            .forEach { (s, n) -> assertEquals(s, n, IntentParser.number(s)) }
    }

    @Test fun `노래를 그만은 촬영 중지가 아니라 BGM 끄기`() {
        assertEquals(Intent.BgmStop, intent("차즈 노래 그만"))
    }

    @Test fun `모르는 말은 Unknown (LLM 폴백 대상)`() {
        assertEquals(Intent.Unknown, intent("차즈 오늘 날씨 어때"))
        assertEquals(Intent.Unknown, intent("차즈 사진 좀 여러 장 박아줘")) // '여러 장'은 숫자가 아님
    }
}
