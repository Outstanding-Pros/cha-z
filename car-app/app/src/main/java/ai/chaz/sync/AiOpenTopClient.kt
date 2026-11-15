package ai.chaz.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * ✨ AI 오픈카 (서버 POST /frames/opentop → 비즈라우터 Gemini 이미지 편집, 약 12초).
 * 실내 사진(+ 같은 순간 후방 풍경)을 보내고 JPEG 를 받는다. 키는 서버에만 있다.
 */
class AiOpenTopClient(private val baseUrl: String, private val timeoutMs: Int = 90_000) {

    class Failed(message: String) : Exception(message)

    suspend fun open(interior: Bitmap, exterior: Bitmap?): Bitmap =
        post("/frames/opentop", listOfNotNull("interior" to (interior to 1600), exterior?.let { "exterior" to (it to 1024) }), emptyMap(), "AI 편집에 실패했어요")

    /**
     * 앨범 만들기 (POST /frames/mix): 실내 사진 + 같은 순간 바깥 풍경 → 한 장.
     * view = "FRONT" | "REAR" (사람을 그 풍경 앞에) | "PANORAMA" (exterior=전방, exterior2=후방을 잇고 가운데에 사람).
     */
    suspend fun mix(interior: Bitmap, exterior: Bitmap, view: String, exterior2: Bitmap? = null): Bitmap =
        post(
            "/frames/mix",
            listOfNotNull("interior" to (interior to 1600), "exterior" to (exterior to 1280), exterior2?.let { "exterior2" to (it to 1280) }),
            mapOf("view" to view), "AI로 만들지 못했어요",
        )

    /** ② 오션 브릿지: 전방+후방 → 넓은 풍경 파노라마 (사람 없음, 얼굴 안 보냄). */
    suspend fun scenery(front: Bitmap, rear: Bitmap): Bitmap =
        post("/frames/scenery", listOf("front" to (front to 1280), "rear" to (rear to 1280)), emptyMap(), "파노라마를 만들지 못했어요")

    /** ③ 차박 나이트: 창밖만 별하늘 + 웜톤 실내. */
    suspend fun night(interior: Bitmap): Bitmap =
        post("/frames/night", listOf("interior" to (interior to 1600)), emptyMap(), "별하늘을 만들지 못했어요")

    /** ④ 펫 드라이버: 운전석 사람 ↔ 조수석 반려견. */
    suspend fun petSwap(interior: Bitmap): Bitmap =
        post("/frames/petswap", listOf("interior" to (interior to 1600)), emptyMap(), "자리 바꾸기를 못 했어요")

    /** ① 트립 로그 / ⑤ 로드 무비 카피. 실패하면 null (템플릿 카피로). */
    suspend fun copy(style: String, place: String?, date: String, time: String, spots: List<String> = emptyList()): org.json.JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = ServerHttp.open("$baseUrl/reel/copy").apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 5_000; readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                val body = org.json.JSONObject().put("style", style).put("place", place).put("date", date).put("time", time)
                    .put("spots", org.json.JSONArray(spots.take(12)))
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode != 200) null else org.json.JSONObject(conn.inputStream.bufferedReader().readText())
            } finally { conn.disconnect() }
        }.getOrNull()
    }

    private suspend fun post(path: String, images: List<Pair<String, Pair<Bitmap, Int>>>, fields: Map<String, String>, failText: String): Bitmap = withContext(Dispatchers.IO) {
        val boundary = "chaz" + System.nanoTime()
        val conn = ServerHttp.open("$baseUrl$path").apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                fun part(name: String, bmp: Bitmap, maxSide: Int) {
                    out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$name.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                    out.write(jpeg(bmp, maxSide))
                    out.writeBytes("\r\n")
                }
                fields.forEach { (k, v) -> out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
                images.forEach { (name, img) -> part(name, img.first, img.second) }
                out.writeBytes("--$boundary--\r\n")
            }
            when (val code = conn.responseCode) {
                200 -> conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: throw Failed("결과 이미지를 읽지 못했어요")
                429 -> throw Failed("오늘 AI 사용량을 다 썼어요")
                else -> throw Failed("$failText ($code)")
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw Failed("AI 응답이 너무 늦어요")
        } catch (e: java.io.IOException) {
            throw Failed("서버에 연결할 수 없어요")
        } finally {
            conn.disconnect()
        }
    }

    private fun jpeg(bmp: Bitmap, maxSide: Int): ByteArray {
        val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val src = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        return ByteArrayOutputStream().also { src.compress(Bitmap.CompressFormat.JPEG, 90, it); if (src !== bmp) src.recycle() }.toByteArray()
    }
}
