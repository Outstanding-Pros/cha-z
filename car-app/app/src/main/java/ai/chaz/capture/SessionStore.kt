package ai.chaz.capture

import ai.chaz.data.Clip
import ai.chaz.data.Cut
import ai.chaz.data.Frame
import ai.chaz.data.Session
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 세션 원본 저장소. 엔진은 Bitmap 을 직접 다루지 않고 여기에 "이 소스들로 컷을 찍어 저장해"라고만 한다
 * (엔진을 에뮬레이터 없이 단위 테스트하기 위해).
 * TODO(T10): Room 메타데이터 + WorkManager 업로드 큐.
 */
interface SessionStore {
    fun begin(session: Session)
    /** 같은 시각에 모든 소스를 캡처해 저장한다. */
    suspend fun saveCut(session: Session, index: Int, tMs: Long, sources: List<CameraSource>): Cut
    /** 클립 스냅샷 한 장. 프레임이 아직 없으면 false. */
    suspend fun saveClipFrame(session: Session, source: CameraSource, seq: Int): Boolean
    fun clipPath(session: Session, role: CameraRole): String
    suspend fun finish(session: Session, cuts: List<Cut>, clips: List<Clip>, stopped: Boolean)
}

/** filesDir/sessions/{id}/ 에 JPEG 와 session.json 을 쓴다. */
class FileSessionStore(context: Context) : SessionStore {
    private val root = File(context.filesDir, "sessions")

    private fun dir(session: Session) = File(root, session.id)

    override fun begin(session: Session) {
        File(dir(session), "cuts").mkdirs()
        File(dir(session), "clips").mkdirs()
    }

    override suspend fun saveCut(session: Session, index: Int, tMs: Long, sources: List<CameraSource>): Cut = coroutineScope {
        // 실내 → 전방 → 후방을 거의 동시에 캡처 (에뮬레이터는 실제 카메라가 1대라 충돌 없음)
        val shots = sources.map { s -> async { s to runCatching { s.capture() }.getOrNull() } }.awaitAll()
        val frames = withContext(Dispatchers.IO) {
            shots.mapNotNull { (s, bmp) ->
                bmp ?: return@mapNotNull null
                val f = File(dir(session), "cuts/%02d_%s.jpg".format(index, s.role.name.lowercase()))
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                Frame(s.role, f.absolutePath, s.kind)
            }
        }
        Cut(session.id, index, tMs, frames)
    }

    override suspend fun saveClipFrame(session: Session, source: CameraSource, seq: Int): Boolean {
        val bmp = runCatching { source.snapshot() }.getOrNull() ?: return false
        return withContext(Dispatchers.IO) {
            val d = File(clipPath(session, source.role)).apply { mkdirs() }
            File(d, "%04d.jpg".format(seq)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            true
        }
    }

    override fun clipPath(session: Session, role: CameraRole): String =
        File(dir(session), "clips/${role.name.lowercase()}").absolutePath

    override suspend fun finish(session: Session, cuts: List<Cut>, clips: List<Clip>, stopped: Boolean) = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("id", session.id)
            .put("startedAt", session.startedAt)
            .put("trigger", session.trigger.name)
            .put("spotName", session.spotName ?: JSONObject.NULL)
            .put("status", session.status.name)
            .put("durationSec", session.config.durationSec)
            .put("count", session.config.count)
            .put("stopped", stopped)
            .put("sourceKinds", JSONArray((cuts.flatMap { c -> c.frames.map { it.source } } + clips.map { it.source }).distinct().map { it.name }))
            .put("cuts", JSONArray(cuts.map { c ->
                JSONObject().put("index", c.index).put("tMs", c.tMs).put("frames", JSONArray(c.frames.map { f ->
                    JSONObject().put("role", f.role.name).put("path", File(f.path).relativeTo(dir(session)).path).put("source", f.source.name)
                }))
            }))
            .put("clips", JSONArray(clips.map { c ->
                JSONObject().put("role", c.role.name).put("source", c.source.name)
                    .put("path", File(c.path).relativeTo(dir(session)).path)
                    .put("durationMs", c.durationMs).put("frameCount", c.frameCount).put("fps", 2)
            }))
        File(dir(session), "session.json").writeText(json.toString(2))
    }
}
