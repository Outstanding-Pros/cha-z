package ai.chaz.sync

import ai.chaz.capture.CameraRole
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneAlbumTest {
    private val full = PhoneBundle(
        "b1", 0,
        mapOf(CameraRole.INTERIOR to "/a/interior.jpg", CameraRole.FRONT to "/a/front.jpg", CameraRole.REAR to "/a/rear.jpg"),
    )

    @Test fun `기본은 찍은 자리 그대로, 없는 자리는 비움`() {
        assertEquals(
            mapOf(CameraRole.INTERIOR to CameraRole.INTERIOR, CameraRole.FRONT to CameraRole.FRONT, CameraRole.REAR to CameraRole.REAR),
            PhoneAlbum.defaultSlots(full),
        )
        val noRear = full.copy(photos = full.photos - CameraRole.REAR)
        assertEquals(null, PhoneAlbum.defaultSlots(noRear)[CameraRole.REAR])
    }

    @Test fun `자리마다 고른 사진 파일로 바꾸고 비운 자리는 뺀다`() {
        val slots = mapOf(CameraRole.INTERIOR to CameraRole.INTERIOR, CameraRole.FRONT to CameraRole.REAR, CameraRole.REAR to null)
        assertEquals(
            mapOf(CameraRole.INTERIOR to "/a/interior.jpg", CameraRole.FRONT to "/a/rear.jpg"),
            PhoneAlbum.resolve(full, slots),
        )
    }

    @Test fun `같은 사진을 두 자리에 넣을 수 있다`() {
        val slots = mapOf(CameraRole.FRONT to CameraRole.FRONT, CameraRole.REAR to CameraRole.FRONT)
        assertEquals(mapOf(CameraRole.FRONT to "/a/front.jpg", CameraRole.REAR to "/a/front.jpg"), PhoneAlbum.resolve(full, slots))
    }

    @Test fun `서버 roles 는 아는 자리만`() {
        assertEquals(listOf(CameraRole.INTERIOR, CameraRole.REAR), PhoneAlbum.parseRoles(listOf("INTERIOR", "LEFT", "x", "REAR", "REAR")))
    }
}
