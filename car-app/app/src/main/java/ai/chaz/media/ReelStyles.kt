package ai.chaz.media

/** 스타일이 받는 재료. PICKS = 앨범 사진 1~4장, VIDEO = 동영상 촬영 한 번(전방 영상 + 실내 4컷), DAY = 같은 날의 촬영들. */
enum class ReelInput { PICKS, VIDEO, DAY }

/**
 * 릴스 스타일 (스타일 기획서 ①~⑤ + 직접 연출). 노출 조건·AI 단계·실패 시 대체 경로를 한곳에.
 * 지금은 앱 코드에 두지만 필드가 기획서의 JSON 프리셋(id, 이름, 노출 조건, 셀렉 규칙, AI 단계, 대체 경로)과 같아 서버 배포로 옮기기 쉽다.
 */
enum class ReelStyle(
    val no: String,
    val label: String,
    val desc: String,
    val input: ReelInput,
    /** AI 단계 (null = 없음). */
    val aiStep: String?,
    /** AI 단계가 얼굴 사진을 서버로 보내나 (동의 필요). */
    val faceAi: Boolean,
    /** AI 를 끄거나 실패했을 때. */
    val fallback: String,
    val minPicks: Int = 1,
    val maxPicks: Int = 4,
) {
    BASIC("", "직접 연출", "사진 1~4장을 그리드·이어붙이기·쌓아서 공개·크롭줌으로", ReelInput.PICKS, null, false, ""),
    TRIP("①", "트립 로그", "하루 여정을 스팟별 컷으로 엮은 여행 기록 (경로선 → 스팟 카드 → 4컷 → 요약)", ReelInput.DAY,
        "AI 여정 제목·카피", false, "템플릿 제목 'M월 d일의 드라이브'"),
    OCEAN("②", "오션 브릿지", "어둠에서 바다로 터지는 순간, 흐르는 풍경 위로 실내 4컷이 차례로", ReelInput.VIDEO,
        "AI 풍경 파노라마(전방+후방 잇기, 사람 사진은 안 보냄)", false, "전방 영상을 크롭해 패닝만"),
    NIGHT("③", "차박 나이트", "노을 주행에서 별밤 차박까지 — 창밖은 별하늘로", ReelInput.PICKS,
        "AI 창밖 별하늘(실내 사진 전송)", true, "블루톤 + 웜 실내 색보정", minPicks = 2),
    PET("④", "펫 드라이버", "마지막 컷에서 반려견과 운전자가 자리를 바꾸는 반전형", ReelInput.PICKS,
        "AI 자리 바꾸기(실내 사진 전송)", true, "좌우 반전 + 앞발 스티커"),
    MOVIE("⑤", "로드 무비", "\"어서 타, 시간이 없어\" — 레터박스 영화 예고편", ReelInput.VIDEO,
        "AI 예고편 카피·가상 제목", false, "템플릿 카피"),
    ;

    val title get() = if (no.isEmpty()) label else "$no $label"

    companion object {
        /** 노출 조건 (기획서 '스타일 후보 계산'). videos = 전방 영상이 있는 동영상 촬영 수, sessionsToday = 오늘 촬영 수. */
        fun available(s: ReelStyle, videos: Int, sessionsToday: Int, photos: Int): Boolean = when (s.input) {
            ReelInput.PICKS -> photos >= s.minPicks
            ReelInput.VIDEO -> videos > 0
            ReelInput.DAY -> sessionsToday >= 2
        }

        fun tripTemplateTitle(month: Int, day: Int) = "${month}월 ${day}일의 드라이브"

        /** 로드 무비 템플릿 카피: (제목, 자막 3줄, 태그라인). */
        fun movieTemplate(place: String): Triple<String, List<String>, String> =
            Triple("$place 가는 길", listOf("이번 주말,", "끝없이 이어진 길", "그리고, 우리"), "다음 드라이브, 누구랑?")
    }
}
