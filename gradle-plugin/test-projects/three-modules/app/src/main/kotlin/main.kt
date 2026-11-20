import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import expui.UiMark
import excore.CoreMark
import kotlinx.cinterop.*
import platform.Foundation.NSStringFromClass
import platform.UIKit.*

private const val APP_MARK = "app-0"

@Composable
fun App() {
    var count by remember { mutableStateOf(0) }
    val tick = remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick.value++
        }
    }
    println("RENDER_APP $APP_MARK tick=${tick.value} count=$count")
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF101820)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("APP $APP_MARK", color = Color.White, fontSize = 24.sp)
            Text("from ui and core modules:", color = Color.White, fontSize = 14.sp)
            UiMark(tick.value)
            CoreMark(tick.value)
            Spacer(Modifier.height(16.dp))
            Text("Counter: $count", color = Color.White, fontSize = 32.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { count-- }) { Text("-") }
                Button(onClick = { count++ }) { Text("+") }
            }
        }
    }
}

class AppDelegate : UIResponder, UIApplicationDelegateProtocol {
    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta {}

    @OverrideInit
    constructor() : super()

    private var _window: UIWindow? = null
    override fun window() = _window
    override fun setWindow(window: UIWindow?) { _window = window }

    override fun application(
        application: UIApplication,
        didFinishLaunchingWithOptions: Map<Any?, *>?
    ): Boolean {
        println("APP DID FINISH")
        val w = UIWindow(frame = UIScreen.mainScreen.bounds)
        w.rootViewController = ComposeUIViewController { App() }
        w.makeKeyAndVisible()
        _window = w
        return true
    }
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun main(args: Array<String>) {
    println("APP MAIN args=${args.size}")
    kotlin.native.setUnhandledExceptionHook { e ->
        println("HOOK class=" + (e::class.simpleName ?: "?"))
        println("HOOK msg=" + (e.message ?: "<null>"))
        try { println("HOOK trace=" + e.stackTraceToString()) } catch (t: Throwable) {}
    }
    memScoped {
        val argc = args.size + 1
        val argv = (arrayOf("HR3") + args).map { it.cstr.ptr }.toCValues()
        autoreleasepool {
            UIApplicationMain(argc, argv, null, NSStringFromClass(AppDelegate))
        }
    }
}
