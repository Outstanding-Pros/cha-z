package ai.chaz.sync

import ai.chaz.capture.CameraRole
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 폰에서 직접 찍어 한 번에 보낸 묶음 (실내·전방·후방). photos: 찍은 자리 → 파일 경로. */
data class PhoneBundle(val id: String, val receivedAt: Long, val photos: Map<CameraRole, String>)

/**
 * 폰 묶음 = 차량 카메라(실내·전방·후방)가 같은 순간에 찍은 "한 컷"의 대체.
 * 나중에 실제 차량 카메라가 붙으면 같은 컷 구조(Cut.frames)로 들어오므로 레이아웃·편집 코드는 그대로 쓴다.
 * 갤러리에서 프레임(레이아웃)을 정하고, 프레임의 컷 칸마다 묶음을 하나씩 넣는다. 순수 로직이라 단위 테스트한다.
 */
object PhoneAlbum {
    /** 차~즈 레이아웃이 쓰는 자리. 폰 묶음도 같은 세 자리로 찍는다. */
    val SLOTS = listOf(CameraRole.INTERIOR, CameraRole.FRONT, CameraRole.REAR)

    fun label(role: CameraRole): String = when (role) {
        CameraRole.INTERIOR -> "실내"; CameraRole.FRONT -> "전방"; CameraRole.REAR -> "후방"
        CameraRole.LEFT -> "좌측"; CameraRole.RIGHT -> "우측"
    }

    /** 프레임마다 컷 칸 수. L2 네컷은 4칸(2칸 이상 채우면 만들 수 있음), 나머지는 1칸. */
    fun cutSlots(layout: ai.chaz.media.Layout): Int = if (layout == ai.chaz.media.Layout.CUT_FRAME) 4 else 1
    fun minCuts(layout: ai.chaz.media.Layout): Int = if (layout == ai.chaz.media.Layout.CUT_FRAME) 2 else 1

    /** 칸(빈 칸은 null) → 채운 묶음만 순서대로. 프레임 칸 수를 넘는 것은 버린다. 만들 수 없으면 null. */
    fun cutsFor(layout: ai.chaz.media.Layout, slots: List<PhoneBundle?>): List<PhoneBundle>? =
        slots.take(cutSlots(layout)).filterNotNull().takeIf { it.size >= minCuts(layout) }

    /** 묶음을 넣고 다음 빈 칸 위치. 다 찼으면 마지막 칸에 머문다. */
    fun nextEmpty(slots: List<PhoneBundle?>, from: Int): Int =
        (slots.indices.drop(from + 1) + slots.indices.take(from + 1)).firstOrNull { slots[it] == null } ?: from

    /** 서버 이벤트의 roles 문자열 → 아는 자리만. */
    fun parseRoles(names: List<String>): List<CameraRole> =
        names.mapNotNull { n -> SLOTS.firstOrNull { it.name == n } }.distinct()
}

/**
 * 폰 묶음 앨범 저장소: filesDir/phone_album/{id}/{interior|front|rear}.jpg + bundle.json.
 * 얼굴이 담긴 사진이라 차량 앱 내부 저장소에만 둔다. 갤러리 화면에서 지울 수 있다.
 */
class PhoneAlbumStore(context: Context) {
    private val root = File(context.filesDir, "phone_album")
    private val _bundles = MutableStateFlow<List<PhoneBundle>>(emptyList())
    val bundles: StateFlow<List<PhoneBundle>> = _bundles.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _bundles.value = (root.listFiles() ?: emptyArray()).mapNotNull(::read).sortedByDescending { it.receivedAt }
    }

    suspend fun save(id: String, photos: Map<CameraRole, ByteArray>): PhoneBundle = withContext(Dispatchers.IO) {
        val dir = File(root, id).apply { mkdirs() }
        val paths = photos.mapValues { (role, bytes) ->
            File(dir, role.name.lowercase() + ".jpg").apply { writeBytes(bytes) }.absolutePath
        }
        val b = PhoneBundle(id, System.currentTimeMillis(), paths)
        File(dir, "bundle.json").writeText(
            JSONObject().put("id", id).put("receivedAt", b.receivedAt)
                .put("roles", org.json.JSONArray(paths.keys.map { it.name })).toString(),
        )
        _bundles.value = (listOf(b) + _bundles.value.filter { it.id != id }).sortedByDescending { it.receivedAt }
        b
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        _bundles.value = _bundles.value.filter { it.id != id }
    }

    private fun read(dir: File): PhoneBundle? = runCatching {
        val j = JSONObject(File(dir, "bundle.json").readText())
        val roles = PhoneAlbum.parseRoles((0 until j.getJSONArray("roles").length()).map { j.getJSONArray("roles").getString(it) })
        val photos = roles.associateWith { File(dir, it.name.lowercase() + ".jpg") }.filterValues { it.exists() }.mapValues { it.value.absolutePath }
        PhoneBundle(j.getString("id"), j.getLong("receivedAt"), photos).takeIf { photos.isNotEmpty() }
    }.getOrNull()
}
