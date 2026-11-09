package ai.chaz.ambience

import ai.chaz.platform.navi.Geo
import ai.chaz.platform.navi.GeoPoint
import ai.chaz.spot.Spot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** SPEC 도메인 CaptureContext 의 맥락 부분. */
enum class SceneType(val label: String) { COAST("해안"), FOREST("숲"), MOUNTAIN("산"), CITY("도시"), RURAL("시골"), UNKNOWN("-") }
enum class TimeBand(val label: String) { DAY("낮"), GOLDEN_HOUR("노을 시간"), NIGHT("밤") }
enum class Weather(val label: String) { CLEAR("맑음"), CLOUDY("흐림"), RAIN("비"), SNOW("눈") }

data class CaptureContext(
    val scene: SceneType,
    val timeBand: TimeBand,
    val weather: Weather,
    val sunsetMs: Long?,
    /** 날씨·일몰 출처 — "open-meteo" 또는 "clock"(시각으로 추정). */
    val source: String,
)

/**
 * ContextProvider (SPEC F3 입력): 날씨·일몰은 Open-Meteo(1순위), 실패하면 시각으로 추정.
 * 풍경은 가까운 스팟 종류로 정한다(해변 → 해안, 봉우리 → 산). 10분 캐시.
 */
class ContextProvider(private val useNetwork: () -> Boolean, private val now: () -> Long = { System.currentTimeMillis() }) {

    private data class Sky(val weather: Weather, val sunriseMs: Long?, val sunsetMs: Long?, val at: Long, val source: String)
    private var sky: Sky? = null

    suspend fun current(here: GeoPoint?, spots: List<Spot>): CaptureContext {
        val s = sky?.takeIf { now() - it.at < 10 * 60_000 } ?: fetch(here).also { sky = it }
        return CaptureContext(
            scene = sceneOf(here, spots),
            timeBand = timeBand(now(), s.sunriseMs, s.sunsetMs),
            weather = s.weather,
            sunsetMs = s.sunsetMs,
            source = s.source,
        )
    }

    private suspend fun fetch(here: GeoPoint?): Sky {
        if (here != null && useNetwork()) {
            runCatching { return openMeteo(here) }
        }
        // 시각으로 추정: 한국 11월 일출 07:10, 일몰 17:20 근사
        val zone = ZoneId.of("Asia/Seoul")
        val d = LocalDate.now(zone)
        fun at(h: Int, m: Int) = LocalDateTime.of(d.year, d.month, d.dayOfMonth, h, m).atZone(zone).toInstant().toEpochMilli()
        return Sky(Weather.CLEAR, at(7, 10), at(17, 20), now(), "clock")
    }

    private suspend fun openMeteo(p: GeoPoint): Sky = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
            "&current=weather_code&daily=sunrise,sunset&timezone=auto&forecast_days=1&timeformat=unixtime"
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 3_000; readTimeout = 4_000 }
        try {
            check(c.responseCode == 200) { "Open-Meteo HTTP ${c.responseCode}" }
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val code = j.getJSONObject("current").getInt("weather_code")
            val daily = j.getJSONObject("daily")
            Sky(
                weatherOf(code),
                daily.getJSONArray("sunrise").getLong(0) * 1000,
                daily.getJSONArray("sunset").getLong(0) * 1000,
                now(),
                "open-meteo",
            )
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** WMO weather code → 날씨. */
        fun weatherOf(code: Int): Weather = when (code) {
            0, 1 -> Weather.CLEAR
            2, 3, 45, 48 -> Weather.CLOUDY
            in 71..77, 85, 86 -> Weather.SNOW
            else -> if (code >= 51) Weather.RAIN else Weather.CLOUDY
        }

        /** 일몰 전후 30분 = 노을 시간, 일몰 30분 뒤 ~ 일출 전 = 밤. */
        fun timeBand(nowMs: Long, sunriseMs: Long?, sunsetMs: Long?): TimeBand {
            if (sunsetMs == null) return TimeBand.DAY
            val w = 30 * 60_000L
            return when {
                kotlin.math.abs(nowMs - sunsetMs) <= w -> TimeBand.GOLDEN_HOUR
                nowMs > sunsetMs + w -> TimeBand.NIGHT
                sunriseMs != null && nowMs < sunriseMs -> TimeBand.NIGHT
                else -> TimeBand.DAY
            }
        }

        /** 3km 안 가장 가까운 스팟의 종류로 풍경을 정한다. */
        fun sceneOf(here: GeoPoint?, spots: List<Spot>): SceneType {
            if (here == null) return SceneType.UNKNOWN
            val near = spots.map { it to Geo.distanceM(here, it.point) }.filter { it.second <= 3_000 }.minByOrNull { it.second }?.first
                ?: return SceneType.UNKNOWN
            // 스팟에 풍경 정보가 있으면 그걸 쓴다 (데모 스팟: 해안 전망대 등)
            near.scene?.let { sc -> runCatching { return SceneType.valueOf(sc) } }
            return when (near.kind) {
                "beach" -> SceneType.COAST
                "peak" -> SceneType.MOUNTAIN
                "viewpoint" -> if (spots.any { it.kind == "beach" && Geo.distanceM(here, it.point) <= 3_000 }) SceneType.COAST else SceneType.MOUNTAIN
                else -> SceneType.UNKNOWN
            }
        }
    }
}
