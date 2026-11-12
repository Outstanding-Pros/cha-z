package ai.chaz.sync

/**
 * 여정 앨범 자동 전송 판정 (기획서 '도착(도어 열림) → 여정 앨범 폰 전송').
 * 순수 함수 — 문이 닫힘→열림으로 바뀐 순간에만, 정차 중이고, 설정이 켜져 있고, 폰이 연결돼 있고,
 * 아직 안 보낸 결과물이 있을 때 보낸다. 문을 여닫을 때마다 다시 보내지 않게 쿨다운을 둔다.
 */
object ArrivalAlbumPolicy {
    const val COOLDOWN_MS = 2 * 60_000L

    enum class Decision { SEND, SKIP_DISABLED, SKIP_DRIVING, SKIP_NOT_PAIRED, SKIP_NOTHING, SKIP_COOLDOWN, NO_EDGE }

    fun decide(
        wasOpen: Boolean?,
        isOpen: Boolean?,
        enabled: Boolean,
        driving: Boolean,
        paired: Boolean,
        pending: Int,
        nowMs: Long,
        lastAutoSendMs: Long?,
    ): Decision = when {
        !(wasOpen == false && isOpen == true) -> Decision.NO_EDGE
        !enabled -> Decision.SKIP_DISABLED
        driving -> Decision.SKIP_DRIVING
        !paired -> Decision.SKIP_NOT_PAIRED
        pending <= 0 -> Decision.SKIP_NOTHING
        lastAutoSendMs != null && nowMs - lastAutoSendMs < COOLDOWN_MS -> Decision.SKIP_COOLDOWN
        else -> Decision.SEND
    }
}
