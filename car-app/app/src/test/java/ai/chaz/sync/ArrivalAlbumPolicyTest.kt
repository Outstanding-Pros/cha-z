package ai.chaz.sync

import ai.chaz.sync.ArrivalAlbumPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalAlbumPolicyTest {
    private fun d(
        was: Boolean? = false, now: Boolean? = true, enabled: Boolean = true, driving: Boolean = false,
        paired: Boolean = true, pending: Int = 3, t: Long = 1_000_000, last: Long? = null,
    ) = ArrivalAlbumPolicy.decide(was, now, enabled, driving, paired, pending, t, last)

    @Test fun `정차 중 문이 열리면 보낸다`() = assertEquals(Decision.SEND, d())
    @Test fun `열린 채로 유지되면 다시 보내지 않는다`() = assertEquals(Decision.NO_EDGE, d(was = true, now = true))
    @Test fun `닫힐 때는 보내지 않는다`() = assertEquals(Decision.NO_EDGE, d(was = true, now = false))
    @Test fun `처음 값을 모르면 보내지 않는다`() = assertEquals(Decision.NO_EDGE, d(was = null))
    @Test fun `설정이 꺼져 있으면 보내지 않는다`() = assertEquals(Decision.SKIP_DISABLED, d(enabled = false))
    @Test fun `주행 중이면 보내지 않는다`() = assertEquals(Decision.SKIP_DRIVING, d(driving = true))
    @Test fun `폰이 없으면 보내지 않는다`() = assertEquals(Decision.SKIP_NOT_PAIRED, d(paired = false))
    @Test fun `보낼 사진이 없으면 보내지 않는다`() = assertEquals(Decision.SKIP_NOTHING, d(pending = 0))
    @Test fun `2분 안에 다시 열면 보내지 않는다`() = assertEquals(Decision.SKIP_COOLDOWN, d(t = 1_000_000, last = 1_000_000 - 60_000))
    @Test fun `2분이 지나면 다시 보낸다`() = assertEquals(Decision.SEND, d(t = 1_000_000, last = 1_000_000 - 3 * 60_000))
}
