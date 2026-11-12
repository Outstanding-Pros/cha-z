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

    private fun car(id: String, cut: Int, interior: ai.chaz.capture.SourceKind) = b(id).copy(
        origin = AlbumOrigin.CAR_VIDEO, group = "g", cut = cut,
        sources = mapOf(CameraRole.INTERIOR to interior, CameraRole.FRONT to ai.chaz.capture.SourceKind.AI_GENERATED, CameraRole.REAR to ai.chaz.capture.SourceKind.AI_GENERATED),
    )

    @Test fun `오픈카 칸에는 실내를 차량 카메라로 찍은 묶음만`() {
        val vehicle = car("v", 1, ai.chaz.capture.SourceKind.WEBCAM)
        val phoneInterior = car("p", 1, ai.chaz.capture.SourceKind.PHONE_CAMERA)
        assertEquals(true, PhoneAlbum.accepts(Layout.WINDOW_SWAP, vehicle))
        assertEquals(false, PhoneAlbum.accepts(Layout.WINDOW_SWAP, phoneInterior))
        assertEquals(false, PhoneAlbum.accepts(Layout.WINDOW_SWAP, a)) // 폰 묶음
        assertEquals(true, PhoneAlbum.accepts(Layout.CUT_FRAME, a))
        assertNull(PhoneAlbum.cutsFor(Layout.WINDOW_SWAP, listOf(a)))
        assertEquals(listOf(vehicle), PhoneAlbum.cutsFor(Layout.WINDOW_SWAP, listOf(vehicle)))
    }

    @Test fun `방금 찍은 동영상은 네컷 4칸에 컷 순서대로, 단발은 지금 프레임에`() {
        val cuts = listOf(3, 1, 4, 2).map { car("c$it", it, ai.chaz.capture.SourceKind.WEBCAM) }
        val (layout, picks) = PhoneAlbum.draftFor(cuts, Layout.ROUTE_OVERLAY)
        assertEquals(Layout.CUT_FRAME, layout)
        assertEquals(listOf(1, 2, 3, 4), picks.map { it.cut })
        val one = car("s", 1, ai.chaz.capture.SourceKind.WEBCAM)
        assertEquals(Layout.ROUTE_OVERLAY to listOf(one), PhoneAlbum.draftFor(listOf(one), Layout.ROUTE_OVERLAY))
    }

    @Test fun `네컷 칸 기본 사진은 실내, 없으면 전방`() {
        assertEquals(AlbumPick.Photo(a, CameraRole.INTERIOR), PhoneAlbum.firstPhoto(a))
        assertEquals(CameraRole.FRONT, PhoneAlbum.firstPhoto(a.copy(photos = a.photos - CameraRole.INTERIOR))?.role)
        assertNull(PhoneAlbum.firstPhoto(a.copy(photos = emptyMap())))
        assertEquals("a/REAR", AlbumPick.Photo(a, CameraRole.REAR).key)
    }

    @Test fun `만들기 기능은 필요한 사진이 있는 묶음만, id 는 서로 다르다`() {
        val noRear = a.copy(photos = a.photos - CameraRole.REAR)
        assertEquals(true, AlbumTools.byId("person_front")!!.accepts(noRear))
        assertEquals(false, AlbumTools.byId("person_rear")!!.accepts(noRear))
        assertEquals(false, AlbumTools.Panorama.accepts(noRear))
        assertEquals(true, AlbumTools.Panorama.accepts(a))
        assertEquals(false, AlbumTools.Collage.ai)
        assertEquals(AlbumTools.all.size, AlbumTools.all.map { it.id }.toSet().size)
    }

    @Test fun `서버 roles 는 아는 자리만`() {
        assertEquals(listOf(CameraRole.INTERIOR, CameraRole.REAR), PhoneAlbum.parseRoles(listOf("INTERIOR", "LEFT", "x", "REAR", "REAR")))
    }
}
