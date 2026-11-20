package expui

import platform.UIKit.UIDevice

actual fun platformLabel(): String = "ios-1(${UIDevice.currentDevice.systemName})"

actual class Greeter actual constructor() {
    actual fun greet(name: String): String = "hi-$name-ios-1"
}
