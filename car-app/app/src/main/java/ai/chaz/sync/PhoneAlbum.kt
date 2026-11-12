package ai.chaz.sync

import ai.chaz.capture.CameraRole
import ai.chaz.capture.SourceKind
import ai.chaz.media.Layout
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 묶음을 어디서 찍었나. */
enum class AlbumOrigin(val label: String) { PHONE("폰"), CAR_SINGLE("단발"), CAR_VIDEO("동영상") }

/**
 * 앨범 묶음 = 같은 순간의 실내·전방·후방 (한 컷). photos: 자리 → 파일 경로.
 * 폰에서 3장 찍어 보낸 것, 또는 차~즈 촬영(단발 1개 / 동영상 4개)의 컷. group = 같은 촬영(세션) id, cut = 그 안의 컷 번호.
 * sources: 자리마다 찍은 카메라 종류 (없으면 폰).
 */
data class PhoneBundle(
    val id: String,
    val receivedAt: Long,
    val photos: Map<CameraRole, String>,
    val origin: AlbumOrigin = AlbumOrigin.PHONE,
    val group: String? = null,
    val cut: Int = 0,
    val sources: Map<CameraRole, SourceKind> = emptyMap(),
) {
    fun sourceOf(role: CameraRole): SourceKind = sources[role] ?: SourceKind.PHONE_CAMERA
    /** 실내를 폰으로 찍었으면 L1 창문 마스크(고정 차량 카메라 전용)를 못 쓴다. */
    val interiorByPhone: Boolean get() = photos.containsKey(CameraRole.INTERIOR) && sourceOf(CameraRole.INTERIOR) == SourceKind.PHONE_CAMERA
}

/** 앨범 '만들기'로 묶음에서 만든 사진 한 장 (앨범의 독립 아이템). source = 만든 묶음 id(지워졌을 수 있음). */
data class MadePhoto(val id: String, val at: Long, val path: String, val toolId: String, val label: String, val ai: Boolean, val sourceId: String?)

/**
 * 프레임 칸 하나에 넣는 앨범 아이템 = 사진 한 장. 묶음의 실내·전방·후방 중 한 장, 또는 만든 사진.
 */
sealed interface AlbumPick {
    val key: String
    val path: String
    val ai: Boolean
    val label: String

    data class Photo(val bundle: PhoneBundle, val role: CameraRole) : AlbumPick {
        override val key get() = "${bundle.id}/${role.name}"
        override val path get() = bundle.photos[role] ?: ""
        override val ai get() = false
        override val label get() = PhoneAlbum.label(role)
    }

    data class Made(val photo: MadePhoto) : AlbumPick {
        override val key get() = photo.id
        override val path get() = photo.path
        override val ai get() = photo.ai
        override val label get() = photo.label
    }
}

/** 동영상 촬영의 12초 영상. clips: 자리 → 0.5초 스냅샷 폴더(0000.jpg…). */
data class AlbumVideo(val id: String, val group: String, val at: Long, val durationMs: Long, val clips: Map<CameraRole, String>, val frameCount: Int)

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
    fun cutSlots(layout: Layout): Int = if (layout == Layout.CUT_FRAME) 4 else 1
    fun minCuts(layout: Layout): Int = if (layout == Layout.CUT_FRAME) 2 else 1

    /** 이 프레임 칸에 올릴 수 있는 묶음인가. L1 오픈카는 실내를 차량 카메라로 찍은 묶음만. */
    fun accepts(layout: Layout, b: PhoneBundle): Boolean = layout != Layout.WINDOW_SWAP || !b.interiorByPhone

    /** 방금 찍은 촬영을 편집 탭 칸에 미리 넣는 초안: 동영상(여러 컷) → 네컷, 단발 → 지금 프레임 그대로. */
    fun draftFor(bundles: List<PhoneBundle>, current: Layout): Pair<Layout, List<PhoneBundle>> {
        val sorted = bundles.sortedBy { it.cut }
        val layout = if (sorted.size > 1) Layout.CUT_FRAME else current
        return layout to sorted.take(cutSlots(layout))
    }

    /** 묶음을 네컷 칸에 한 장으로 넣을 때 기본 사진: 실내(없으면 전방, 후방). */
    fun firstPhoto(b: PhoneBundle): AlbumPick.Photo? = SLOTS.firstOrNull { b.photos.containsKey(it) }?.let { AlbumPick.Photo(b, it) }

    /** 칸(빈 칸은 null) → 채운 묶음만 순서대로. 프레임 칸 수를 넘는 것은 버린다. 만들 수 없으면 null. */
    fun cutsFor(layout: Layout, slots: List<PhoneBundle?>): List<PhoneBundle>? =
        slots.take(cutSlots(layout)).filterNotNull().takeIf { it.size >= minCuts(layout) && it.all { b -> accepts(layout, b) } }

    /** 묶음을 넣고 다음 빈 칸 위치. 다 찼으면 마지막 칸에 머문다. */
    fun <T> nextEmpty(slots: List<T?>, from: Int): Int =
        (slots.indices.drop(from + 1) + slots.indices.take(from + 1)).firstOrNull { slots[it] == null } ?: from

    /** 서버 이벤트의 roles 문자열 → 아는 자리만. */
    fun parseRoles(names: List<String>): List<CameraRole> =
        names.mapNotNull { n -> SLOTS.firstOrNull { it.name == n } }.distinct()
}

/**
 * 앨범 저장소: filesDir/phone_album/{id}/{interior|front|rear}.jpg + bundle.json (묶음),
 * filesDir/phone_album/{id}/{interior|front|rear}/0000.jpg… + video.json (동영상).
 * 얼굴이 담긴 사진이라 차량 앱 내부 저장소에만 둔다. 편집 탭에서 지울 수 있다.
 */
class PhoneAlbumStore(context: Context) {
    private val root = File(context.filesDir, "phone_album")
    private val _bundles = MutableStateFlow<List<PhoneBundle>>(emptyList())
    val bundles: StateFlow<List<PhoneBundle>> = _bundles.asStateFlow()
    private val _videos = MutableStateFlow<List<AlbumVideo>>(emptyList())
    val videos: StateFlow<List<AlbumVideo>> = _videos.asStateFlow()
    private val _made = MutableStateFlow<List<MadePhoto>>(emptyList())
    val made: StateFlow<List<MadePhoto>> = _made.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val dirs = root.listFiles() ?: emptyArray()
        _bundles.value = dirs.mapNotNull(::read).sortedWith(ORDER)
        _videos.value = dirs.mapNotNull(::readVideo).sortedByDescending { it.at }
        _made.value = dirs.mapNotNull(::readMade).sortedByDescending { it.at }
    }

    /** 폰에서 받은 묶음 (사진 바이트). */
    suspend fun save(id: String, photos: Map<CameraRole, ByteArray>): PhoneBundle = withContext(Dispatchers.IO) {
        val dir = File(root, id).apply { mkdirs() }
        val paths = photos.mapValues { (role, bytes) -> File(dir, file(role)).apply { writeBytes(bytes) }.absolutePath }
        put(write(dir, PhoneBundle(id, System.currentTimeMillis(), paths)))
    }

    /**
     * 차~즈 촬영 결과: 컷마다 묶음 하나(원본 사진 복사), 영상이 있으면 영상 하나(스냅샷 폴더는 옮긴다).
     * 반환: 저장한 묶음(컷 순서).
     */
    suspend fun saveCapture(result: ai.chaz.capture.CaptureSessionEngine.Result): List<PhoneBundle> = withContext(Dispatchers.IO) {
        val group = result.session.id
        val origin = if (result.session.config.video) AlbumOrigin.CAR_VIDEO else AlbumOrigin.CAR_SINGLE
        val at = System.currentTimeMillis()
        val saved = result.cuts.sortedBy { it.index }.mapNotNull { cut ->
            if (cut.frames.isEmpty()) return@mapNotNull null
            val id = "${group}_c${cut.index}"
            val dir = File(root, id).apply { mkdirs() }
            val paths = cut.frames.associate { f -> f.role to File(dir, file(f.role)).also { File(f.path).copyTo(it, overwrite = true) }.absolutePath }
            write(dir, PhoneBundle(id, at + cut.index, paths, origin, group, cut.index, cut.frames.associate { it.role to it.source }))
        }
        val clips = result.clips.filter { it.frameCount > 0 && File(it.path).isDirectory }
        if (clips.isNotEmpty()) {
            val id = "${group}_v"
            val dir = File(root, id).apply { mkdirs() }
            val moved = clips.associate { c ->
                val dst = File(dir, c.role.name.lowercase())
                if (!File(c.path).renameTo(dst)) File(c.path).copyRecursively(dst, overwrite = true)
                c.role to dst.absolutePath
            }
            val v = AlbumVideo(id, group, at, clips.maxOf { it.durationMs }, moved, clips.maxOf { it.frameCount })
            File(dir, "video.json").writeText(
                JSONObject().put("id", id).put("group", group).put("at", at).put("durationMs", v.durationMs).put("frameCount", v.frameCount)
                    .put("roles", org.json.JSONArray(moved.keys.map { it.name })).toString(),
            )
            _videos.value = (listOf(v) + _videos.value.filter { it.id != id }).sortedByDescending { it.at }
        }
        _bundles.value = (saved + _bundles.value.filter { b -> saved.none { it.id == b.id } }).sortedWith(ORDER)
        saved
    }

    /** 앨범 '만들기' 결과 = 앨범의 새 아이템. 폴더 {source}_m_{tool}/made.jpg + made.json. */
    fun madeId(source: PhoneBundle, toolId: String) = "${source.id}_m_$toolId"
    fun made(source: PhoneBundle, toolId: String): MadePhoto? = _made.value.firstOrNull { it.id == madeId(source, toolId) }

    suspend fun saveMade(source: PhoneBundle, tool: AlbumTool, img: android.graphics.Bitmap): MadePhoto = withContext(Dispatchers.IO) {
        val id = madeId(source, tool.id)
        val dir = File(root, id).apply { mkdirs() }
        val f = File(dir, "made.jpg")
        f.outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        val m = MadePhoto(id, System.currentTimeMillis(), f.absolutePath, tool.id, tool.label, tool.ai, source.id)
        File(dir, "made.json").writeText(
            JSONObject().put("id", id).put("at", m.at).put("tool", m.toolId).put("label", m.label).put("ai", m.ai).put("source", source.id).toString(),
        )
        _made.value = (listOf(m) + _made.value.filter { it.id != id }).sortedByDescending { it.at }
        m
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        _bundles.value = _bundles.value.filter { it.id != id }
        _videos.value = _videos.value.filter { it.id != id }
        _made.value = _made.value.filter { it.id != id }
    }

    private fun file(role: CameraRole) = role.name.lowercase() + ".jpg"

    private fun put(b: PhoneBundle): PhoneBundle {
        _bundles.value = (listOf(b) + _bundles.value.filter { it.id != b.id }).sortedWith(ORDER)
        return b
    }

    private fun write(dir: File, b: PhoneBundle): PhoneBundle {
        File(dir, "bundle.json").writeText(
            JSONObject().put("id", b.id).put("receivedAt", b.receivedAt)
                .put("roles", org.json.JSONArray(b.photos.keys.map { it.name }))
                .put("origin", b.origin.name).put("group", b.group ?: JSONObject.NULL).put("cut", b.cut)
                .put("sources", JSONObject().apply { b.sources.forEach { (r, k) -> put(r.name, k.name) } }).toString(),
        )
        return b
    }

    private fun read(dir: File): PhoneBundle? = runCatching {
        val j = JSONObject(File(dir, "bundle.json").readText())
        val roles = PhoneAlbum.parseRoles((0 until j.getJSONArray("roles").length()).map { j.getJSONArray("roles").getString(it) })
        val photos = roles.associateWith { File(dir, file(it)) }.filterValues { it.exists() }.mapValues { it.value.absolutePath }
        val src = j.optJSONObject("sources")
        val sources = roles.mapNotNull { r -> src?.optString(r.name)?.let { n -> runCatching { r to SourceKind.valueOf(n) }.getOrNull() } }.toMap()
        PhoneBundle(
            j.getString("id"), j.getLong("receivedAt"), photos,
            origin = runCatching { AlbumOrigin.valueOf(j.optString("origin")) }.getOrDefault(AlbumOrigin.PHONE),
            group = j.optString("group").takeIf { j.has("group") && !j.isNull("group") && it.isNotBlank() },
            cut = j.optInt("cut", 0), sources = sources,
        ).takeIf { photos.isNotEmpty() }
    }.getOrNull()

    private fun readVideo(dir: File): AlbumVideo? = runCatching {
        val j = JSONObject(File(dir, "video.json").readText())
        val roles = PhoneAlbum.parseRoles((0 until j.getJSONArray("roles").length()).map { j.getJSONArray("roles").getString(it) })
        val clips = roles.associateWith { File(dir, it.name.lowercase()) }.filterValues { it.isDirectory }.mapValues { it.value.absolutePath }
        AlbumVideo(j.getString("id"), j.getString("group"), j.getLong("at"), j.getLong("durationMs"), clips, j.getInt("frameCount")).takeIf { clips.isNotEmpty() }
    }.getOrNull()

    private fun readMade(dir: File): MadePhoto? = runCatching {
        val j = JSONObject(File(dir, "made.json").readText())
        val f = File(dir, "made.jpg").takeIf { it.exists() } ?: return@runCatching null
        MadePhoto(j.getString("id"), j.getLong("at"), f.absolutePath, j.getString("tool"), j.getString("label"), j.optBoolean("ai"),
            j.optString("source").takeIf { it.isNotBlank() })
    }.getOrNull()

    companion object {
        /** 최신 촬영이 위, 같은 촬영 안에서는 컷 순서대로. */
        private val ORDER = compareByDescending<PhoneBundle> { if (it.group != null) it.receivedAt - it.cut else it.receivedAt }.thenBy { it.cut }
    }
}
