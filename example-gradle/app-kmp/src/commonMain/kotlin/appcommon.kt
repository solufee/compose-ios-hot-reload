package appkmp

// App-module common code (Kotlin Multiplatform example): main.kt in iosMain uses it.
expect fun appPlatform(): String

fun appCommon(): String = "appc-1"
