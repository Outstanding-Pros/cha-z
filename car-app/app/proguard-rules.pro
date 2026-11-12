# 차~즈 릴리스 R8 규칙 — 목적은 APK 크기(안 쓰는 라이브러리 코드 제거)뿐이다.
# 이름 변경·최적화는 하지 않는다 (11/10: 부분 최적화에서 IllegalAccessError 크래시 실측).
-dontobfuscate
-dontoptimize
-dontwarn **

# 우리 코드 (작다. 리플렉션·Compose 안전하게 전부 유지)
-keep class ai.chaz.** { *; }

# Pleos SDK (Vehicle·NaviHelper·TTS·STT, AIDL 바인더)
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-keep class * extends android.os.IInterface { *; }
-keep class * extends android.os.Binder { *; }

# MediaPipe Tasks (JNI 가 자바 클래스·필드를 이름으로 찾는다) + protobuf
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }

# JNA (libjnidispatch — SDK 음성 엔진이 사용)
-keep class com.sun.jna.** { *; }

# JNI 네이티브 메서드
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# 제네릭·내부 클래스 정보 유지 — Pleos Vehicle SDK 가 Gson TypeToken 으로 응답을 파싱한다
# (11/12 실측: 지우면 Light·Door·Odometer 가 "TypeToken must be created with a type argument" 로 실패)
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }
