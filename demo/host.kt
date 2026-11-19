import platform.posix.sleep

fun hello(): String = "original"

fun main() {
    while (true) {
        println("value=" + hello())
        sleep(2u)
    }
}
