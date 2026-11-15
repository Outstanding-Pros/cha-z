package ai.chaz.sync

import ai.chaz.media.ReelMotion
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 앨범의 릴스 한 편 (세로 9:16 MP4 + 커버). */
data class AlbumReel(
    val id: String, val at: Long, val motion: ReelMotion, val count: Int, val ai: Boolean,
    val durationMs: Long, val videoPath: String, val coverPath: String,
)

/** filesDir/reels/{id}/ reel.mp4 · cover.jpg · reel.json */
class ReelStore(context: Context) {
    private val root = File(context.filesDir, "reels")
    private val _reels = MutableStateFlow<List<AlbumReel>>(emptyList())
    val reels: StateFlow<List<AlbumReel>> = _reels.asStateFlow()

    fun newDir(id: String): File = File(root, id).apply { mkdirs() }

    suspend fun load() = withContext(Dispatchers.IO) {
        _reels.value = (root.listFiles() ?: emptyArray()).mapNotNull(::read).sortedByDescending { it.at }
    }

    suspend fun add(r: AlbumReel) = withContext(Dispatchers.IO) {
        File(File(r.videoPath).parentFile, "reel.json").writeText(
            JSONObject().put("id", r.id).put("at", r.at).put("motion", r.motion.name).put("count", r.count)
                .put("ai", r.ai).put("durationMs", r.durationMs).toString(),
        )
        _reels.update { listOf(r) + it.filter { x -> x.id != r.id } }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        _reels.update { it.filter { r -> r.id != id } }
    }

    private fun read(dir: File): AlbumReel? = runCatching {
        val j = JSONObject(File(dir, "reel.json").readText())
        val v = File(dir, "reel.mp4"); val c = File(dir, "cover.jpg")
        if (!v.exists()) return null
        AlbumReel(j.getString("id"), j.getLong("at"), ReelMotion.valueOf(j.getString("motion")), j.getInt("count"),
            j.optBoolean("ai"), j.getLong("durationMs"), v.absolutePath, c.absolutePath)
    }.getOrNull()
}
