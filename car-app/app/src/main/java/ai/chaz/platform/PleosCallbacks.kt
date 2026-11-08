package ai.chaz.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pleos SDK 의 (onComplete, onFailed) 콜백 쌍을 suspend 로 바꾼다.
 * 콜백이 두 번 오거나 취소 뒤에 와도 무시한다.
 */
suspend fun <T> awaitCallback(call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit): T =
    suspendCancellableCoroutine { cont ->
        call(
            { value -> if (cont.isActive) cont.resume(value) },
            { error -> if (cont.isActive) cont.resumeWithException(error) },
        )
    }
