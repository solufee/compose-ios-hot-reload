package appkmp

import platform.Foundation.NSProcessInfo

actual fun appPlatform(): String = "appios-1(cpu=${NSProcessInfo.processInfo.activeProcessorCount})"
