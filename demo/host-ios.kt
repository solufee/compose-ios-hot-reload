import platform.Foundation.NSThread

fun hello(): String = "patched2"

fun main() {
    while (true) {
        println("value=" + hello())
        NSThread.sleepForTimeInterval(2.0)
    }
}
