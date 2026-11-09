package ai.chaz.spot

import ai.chaz.platform.navi.Geo
import ai.chaz.platform.navi.GeoPoint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 사진 스팟. kind = viewpoint | beach | attraction | peak | tourapi. */
data class Spot(val id: String, val name: String, val point: GeoPoint, val kind: String, val source: String, val scene: String? = null) {
    /** SPEC F2 점수: viewpoint·beach 3점, attraction·peak·TourAPI 관광지 2점. 2점 이상만 가이드. */
    val score: Int get() = when (kind) {
        "viewpoint", "beach" -> 3
        "attraction", "peak", "tourapi" -> 2
        else -> 0
    }
}

interface SpotSource {
    val name: String
    suspend fun around(center: GeoPoint, radiusM: Int): List<Spot>
}

/** 최후 수단: 앱에 넣은 데모 스팟 목록 (assets/spots/demo_spots.json — 제주 해안도로). */
class DemoSpotSource(private val context: Context) : SpotSource {
    override val name = "demo-spots"
    private val all: List<Spot> by lazy {
        runCatching {
            val arr = JSONArray(context.assets.open("spots/demo_spots.json").bufferedReader().use { it.readText() })
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Spot("demo:" + o.getString("id"), o.getString("name"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")), o.getString("kind"), name, o.optString("scene").ifBlank { null })
            }
        }.getOrDefault(emptyList())
    }
    override suspend fun around(center: GeoPoint, radiusM: Int) = all.filter { Geo.distanceM(center, it.point) <= radiusM }

    companion object {
        fun route(context: Context): List<GeoPoint> = runCatching {
            val arr = JSONArray(context.assets.open("spots/demo_route.json").bufferedReader().use { it.readText() })
            (0 until arr.length()).map { arr.getJSONArray(it).let { p -> GeoPoint(p.getDouble(0), p.getDouble(1)) } }
        }.getOrDefault(emptyList())
    }
}

/** 1순위: OpenStreetMap Overpass (viewpoint·beach·attraction·peak, 이름 있는 것만). TourAPI 는 키가 필요해 이후. */
class OverpassSpotSource(private val endpoint: String = "https://overpass-api.de/api/interpreter") : SpotSource {
    override val name = "overpass"

    override suspend fun around(center: GeoPoint, radiusM: Int): List<Spot> = withContext(Dispatchers.IO) {
        val a = "around:$radiusM,${center.lat},${center.lon}"
        val q = """[out:json][timeout:8];(node["tourism"="viewpoint"]["name"]($a);node["natural"="beach"]["name"]($a);""" +
            """way["natural"="beach"]["name"]($a);node["tourism"="attraction"]["name"]($a);node["natural"="peak"]["name"]($a););out center 60;"""
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 5_000; readTimeout = 10_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "cha-z-car-app/0.1")
        }
        try {
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            if (conn.responseCode != 200) error("Overpass HTTP ${conn.responseCode}")
            val els = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("elements")
            (0 until els.length()).mapNotNull { i ->
                val e = els.getJSONObject(i)
                val tags = e.optJSONObject("tags") ?: return@mapNotNull null
                val name = tags.optString("name:ko").ifBlank { tags.optString("name") }.ifBlank { return@mapNotNull null }
                val lat = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.getDouble("lat") ?: return@mapNotNull null
                val lon = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.getDouble("lon") ?: return@mapNotNull null
                val kind = when {
                    tags.optString("tourism") == "viewpoint" -> "viewpoint"
                    tags.optString("natural") == "beach" -> "beach"
                    tags.optString("natural") == "peak" -> "peak"
                    else -> "attraction"
                }
                Spot("osm:${e.optString("type")}/${e.optLong("id")}", name, GeoPoint(lat, lon), kind, this@OverpassSpotSource.name)
            }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * SpotRepository (SPEC F2): 현재 위치 반경 3km 스팟을 모아 캐시하고, 2km 움직일 때마다 다시 모은다.
 * 데모 스팟과 Overpass 결과를 합치고(100m 안 같은 이름은 하나로), 2점 이상만 남긴다.
 * TODO(T12+): 경로 좌표를 받으면 경로 500m 폭 안의 스팟만 (NaviHelper 경로 좌표 제공 여부 미확인).
 */
class SpotRepository(private val sources: () -> List<SpotSource>) {
    private var cachedAt: GeoPoint? = null
    private var cached: List<Spot> = emptyList()

    fun cachedSpots() = cached

    suspend fun spotsNear(here: GeoPoint, force: Boolean = false): List<Spot> {
        val at = cachedAt
        if (!force && at != null && Geo.distanceM(at, here) < REFRESH_M) return cached
        val found = sources().flatMap { s ->
            runCatching { s.around(here, RADIUS_M) }.onFailure { Log.w("ChazSpot", "${s.name} 실패: ${it.message}") }.getOrDefault(emptyList())
        }
        cached = merge(found).filter { it.score >= 2 }
        cachedAt = here
        Log.i("ChazSpot", "스팟 ${cached.size}곳 (출처: ${cached.groupingBy { it.source }.eachCount()})")
        return cached
    }

    companion object {
        const val RADIUS_M = 3_000
        const val REFRESH_M = 2_000.0

        fun merge(spots: List<Spot>): List<Spot> {
            val out = mutableListOf<Spot>()
            for (s in spots.sortedByDescending { it.score }) {
                if (out.none { it.name == s.name && Geo.distanceM(it.point, s.point) < 100 }) out += s
            }
            return out
        }
    }
}
