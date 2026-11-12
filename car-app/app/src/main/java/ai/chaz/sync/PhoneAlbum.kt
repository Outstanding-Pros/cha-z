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

/** 묶음에서 "어느 사진을 어느 자리(프레임)에 넣을지". 순수 로직이라 단위 테스트한다. */
object PhoneAlbum {
    /** 차~즈 레이아웃이 쓰는 자리. 폰 묶음도 같은 세 자리로 찍는다. */
    val SLOTS = listOf(CameraRole.INTERIOR, CameraRole.FRONT, CameraRole.REAR)

    fun label(role: CameraRole): String = when (role) {
        CameraRole.INTERIOR -> "실내"; CameraRole.FRONT -> "전방"; CameraRole.REAR -> "후방"
        CameraRole.LEFT -> "좌측"; CameraRole.RIGHT -> "우측"
    }

    /** 기본: 찍은 자리 그대로. 없는 자리는 비운다(null). */
    fun defaultSlots(bundle: PhoneBundle): Map<CameraRole, CameraRole?> =
        SLOTS.associateWith { slot -> slot.takeIf { it in bundle.photos } }

    /** 자리 → 실제 파일. 비운 자리·없는 사진은 뺀다. 같은 사진을 두 자리에 넣어도 된다. */
    fun resolve(bundle: PhoneBundle, slots: Map<CameraRole, CameraRole?>): Map<CameraRole, String> =
        slots.mapNotNull { (slot, photo) -> photo?.let { bundle.photos[it] }?.let { slot to it } }.toMap()

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
