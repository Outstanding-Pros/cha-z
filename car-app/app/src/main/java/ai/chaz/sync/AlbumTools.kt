package ai.chaz.sync

import ai.chaz.capture.CameraRole
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * 앨범 '만들기' 기능 하나: 묶음(같은 순간의 실내·전방·후방)을 써서 사진 한 장을 만든다.
 * 결과는 앨범의 새 아이템(MadePhoto)이 되고, 편집 탭에서 프레임 칸에 넣을 수 있다.
 * 새 기능은 이 인터페이스를 구현해 [AlbumTools.all] 에 넣기만 하면 앨범 화면에 버튼이 생긴다.
 */
interface AlbumTool {
    val id: String
    val label: String
    val desc: String
    /** 서버 AI(얼굴 사진이 외부로 나감 → 동의 필요, 비용 있음). false 면 차 안에서 바로 만든다. */
    val ai: Boolean
    /** 필요한 사진 자리. */
    val needs: List<CameraRole>
    fun accepts(b: PhoneBundle): Boolean = needs.all { b.photos.containsKey(it) }
    /** photos 에는 needs 가 다 들어 있다. */
    suspend fun make(photos: Map<CameraRole, Bitmap>, ai: AiOpenTopClient): Bitmap
}

object AlbumTools {
    /** 사람을 바깥 풍경 앞에 세운다 (서버 /frames/mix, view = FRONT|REAR). */
    class PersonOnScenery(private val view: CameraRole) : AlbumTool {
        override val id = "person_" + view.name.lowercase()
        override val label = "사람 + ${PhoneAlbum.label(view)} 풍경"
        override val desc = "실내 사진의 사람들을 차 밖 ${PhoneAlbum.label(view)} 풍경 앞에 세워 한 장으로 (AI, 약 15초)"
        override val ai = true
        override val needs = listOf(CameraRole.INTERIOR, view)
        override suspend fun make(photos: Map<CameraRole, Bitmap>, ai: AiOpenTopClient) =
            ai.mix(photos.getValue(CameraRole.INTERIOR), photos.getValue(view), view.name)
    }

    /** 🌄 파노라마: 전방 → 후방 풍경을 넓게 한 장으로 잇고 가운데에 사람들 (서버 /frames/mix, view = PANORAMA). */
    object Panorama : AlbumTool {
        override val id = "panorama"
        override val label = "파노라마 드라이브"
        override val desc = "전방부터 후방까지 풍경을 넓게 이어 붙이고, 그 가운데에 실내 사진의 사람들을 (AI, 약 20초)"
        override val ai = true
        override val needs = listOf(CameraRole.INTERIOR, CameraRole.FRONT, CameraRole.REAR)
        override suspend fun make(photos: Map<CameraRole, Bitmap>, ai: AiOpenTopClient) =
            ai.mix(photos.getValue(CameraRole.INTERIOR), photos.getValue(CameraRole.FRONT), "PANORAMA", photos.getValue(CameraRole.REAR))
    }

    /** 세 장 모아 한 장: 왼쪽 큰 칸 실내, 오른쪽 위 전방·아래 후방 (차 안에서 바로, 무료). */
    object Collage : AlbumTool {
        override val id = "collage"
        override val label = "세 장 모아 한 장"
        override val desc = "실내를 크게, 전방·후방을 옆에 작게 붙여 한 장으로 (바로 만들어요, 무료)"
        override val ai = false
        override val needs = listOf(CameraRole.INTERIOR, CameraRole.FRONT, CameraRole.REAR)
        override suspend fun make(photos: Map<CameraRole, Bitmap>, ai: AiOpenTopClient): Bitmap {
            val w = 1920; val h = 1080; val gap = 12
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.rgb(250, 247, 240))
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val leftW = (w - gap * 3) * 2 / 3
            val rightW = w - gap * 3 - leftW
            val halfH = (h - gap * 3) / 2
            cover(c, p, photos.getValue(CameraRole.INTERIOR), Rect(gap, gap, gap + leftW, h - gap))
            cover(c, p, photos.getValue(CameraRole.FRONT), Rect(gap * 2 + leftW, gap, w - gap, gap + halfH))
            cover(c, p, photos.getValue(CameraRole.REAR), Rect(gap * 2 + leftW, gap * 2 + halfH, w - gap, h - gap))
            return out
        }

        private fun cover(c: Canvas, p: Paint, src: Bitmap, dst: Rect) {
            val k = maxOf(dst.width().toFloat() / src.width, dst.height().toFloat() / src.height)
            val sw = (dst.width() / k).toInt(); val sh = (dst.height() / k).toInt()
            val sx = (src.width - sw) / 2; val sy = (src.height - sh) / 2
            c.drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), dst, p)
        }
    }

    /** 앨범 화면에 나오는 순서. 새 기능은 여기에 추가. */
    val all: List<AlbumTool> = listOf(PersonOnScenery(CameraRole.FRONT), PersonOnScenery(CameraRole.REAR), Panorama, Collage)

    fun byId(id: String): AlbumTool? = all.firstOrNull { it.id == id }
}
