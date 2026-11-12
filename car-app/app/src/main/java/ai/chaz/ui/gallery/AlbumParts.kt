package ai.chaz.ui.gallery

import ai.chaz.sync.AlbumOrigin
import ai.chaz.sync.PhoneBundle
import ai.chaz.ui.theme.Tokens
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 앨범 탭·편집 탭이 같이 쓰는 조각. */

internal fun originText(b: PhoneBundle): String = when (b.origin) {
    AlbumOrigin.PHONE -> "폰"
    AlbumOrigin.CAR_SINGLE -> "단발"
    AlbumOrigin.CAR_VIDEO -> "동영상 ${b.cut}컷째"
}

internal fun whenText(ms: Long): String = SimpleDateFormat("M월 d일 HH:mm:ss", Locale.KOREA).format(Date(ms))

@Composable
internal fun AlbumTag(text: String, color: androidx.compose.ui.graphics.Color = Tokens.accent) {
    Text(
        text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Tokens.accentSoft, Tokens.controlShape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 사진 한 장을 영역에 꽉 채워(잘라서) 또는 다 보이게(fit) 보여준다. */
@Composable
internal fun PhotoImage(path: String?, modifier: Modifier, crop: Boolean = true, sample: Int = 4) {
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        img = path?.let { p -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() } }
    }
    img?.let { Image(it, null, modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) }
}

@Composable
internal fun Thumb(path: String?, label: String, modifier: Modifier, ratio: Float = 4f / 3f, sample: Int = 8) {
    // 새 사진을 읽는 동안 이전 사진을 그대로 둔다 (영상 재생 때 깜빡임 방지)
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        img = path?.let { p -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() } }
    }
    Box(modifier.aspectRatio(ratio).clip(Tokens.controlShape).background(Tokens.bg)) {
        img?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } // 찍힌 프레임 전체(자르지 않음)
            ?: Text("없음", color = Tokens.text3, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        if (label.isNotEmpty()) Text(
            label, color = Tokens.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).background(Tokens.bg.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 동영상 스냅샷 폴더를 0.5초마다 넘겨 재생. */
@Composable
internal fun ClipThumb(dir: String?, label: String, modifier: Modifier, ratio: Float = 4f / 3f, sample: Int = 8) {
    val files = remember(dir) { dir?.let { d -> File(d).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } }.orEmpty() }
    var i by remember(dir) { mutableStateOf(0) }
    LaunchedEffect(dir, files.size) {
        while (files.size > 1) { delay(500); i = (i + 1) % files.size }
    }
    Thumb(files.getOrNull(i)?.absolutePath, label, modifier, ratio, sample)
}

@Composable
internal fun BundleThumbs(b: PhoneBundle, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ai.chaz.sync.PhoneAlbum.SLOTS.forEach { role -> Thumb(b.photos[role], ai.chaz.sync.PhoneAlbum.label(role), Modifier.weight(1f)) }
    }
}
