package ai.chaz.sync

import ai.chaz.capture.CameraRole
import ai.chaz.media.Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneAlbumTest {
    private fun b(id: String) = PhoneBundle(id, 0, mapOf(CameraRole.INTERIOR to "/$id/i.jpg", CameraRole.FRONT to "/$id/f.jpg", CameraRole.REAR to "/$id/r.jpg"))
    private val a = b("a"); private val c = b("c"); private val d = b("d")

    @Test fun `프레임마다 컷 칸 수 - 네컷 4칸 나머지 1칸`() {
        assertEquals(4, PhoneAlbum.cutSlots(Layout.CUT_FRAME))
        assertEquals(1, PhoneAlbum.cutSlots(Layout.WINDOW_SWAP))
        assertEquals(1, PhoneAlbum.cutSlots(Layout.ROUTE_OVERLAY))
    }

    @Test fun `네컷은 2칸 이상 채워야 만들고 빈 칸은 건너뛰어 순서대로`() {
        assertNull(PhoneAlbum.cutsFor(Layout.CUT_FRAME, listOf(a, null, null, null)))
        assertEquals(listOf(a, d), PhoneAlbum.cutsFor(Layout.CUT_FRAME, listOf(a, null, d, null)))
        assertEquals(listOf(a, a, c), PhoneAlbum.cutsFor(Layout.CUT_FRAME, listOf(a, a, null, c))) // 같은 묶음 여러 칸 가능
    }

    @Test fun `1칸 프레임은 첫 칸만 쓴다`() {
        assertEquals(listOf(c), PhoneAlbum.cutsFor(Layout.ROUTE_OVERLAY, listOf(c, a, d)))
        assertNull(PhoneAlbum.cutsFor(Layout.ROUTE_OVERLAY, listOf(null)))
    }

    @Test fun `넣은 뒤 다음 빈 칸으로`() {
        assertEquals(2, PhoneAlbum.nextEmpty(listOf(a, c, null, null), 1))
        assertEquals(0, PhoneAlbum.nextEmpty(listOf(null, c, d, a), 3)) // 뒤가 다 찼으면 앞의 빈 칸
        assertEquals(1, PhoneAlbum.nextEmpty(listOf(a, c, d, a), 1))   // 다 찼으면 그대로
    }

    @Test fun `서버 roles 는 아는 자리만`() {
        assertEquals(listOf(CameraRole.INTERIOR, CameraRole.REAR), PhoneAlbum.parseRoles(listOf("INTERIOR", "LEFT", "x", "REAR", "REAR")))
    }
}
