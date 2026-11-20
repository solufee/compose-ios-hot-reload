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
import appkmp.appCommon
import appkmp.appPlatform
import expui.UiMark
import expui.UiMark2
import kotlinx.cinterop.*
import platform.Foundation.NSStringFromClass
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.*

private const val APP_MARK = "ab2"

// Warm-restart state: page and counter are the serializable part of the UI
// state (interface v7). The app writes them on every change; the launcher reads
// the same file before rebuilding and passes it back as --hr-state=page,count.
object HrState {
    var page = 0
    var count = 0
    private var dir: String? = null

    private fun documentsDir(): String? {
        @Suppress("UNCHECKED_CAST")
        val dirs = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        ) as? List<String>
        return dirs?.firstOrNull()
    }

    fun restore(args: Array<String>) {
        dir = documentsDir()
        val arg = args.firstOrNull { it.startsWith("--hr-state=") }
        if (arg != null) {
            val parts = arg.substringAfter('=').split(',')
            page = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            println("HR STATE restore page=$page count=$count")
            return
        }
        val path = dir?.let { "$it/hr-state.json" } ?: return
        try {
            val ns = NSString.create(contentsOfFile = path, encoding = NSUTF8StringEncoding, error = null)
            val text = ns.toString()
            val mPage = Regex("\"page\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)
            val mCount = Regex("\"count\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)
            page = mPage?.toIntOrNull() ?: 0
            count = mCount?.toIntOrNull() ?: 0
            println("HR STATE file page=$page count=$count")
        } catch (t: Throwable) {
            println("HR STATE file unreadable: ${t.message}")
        }
    }

    fun save(page: Int, count: Int) {
        val path = dir?.let { "$it/hr-state.json" } ?: return
        val text = "{\"page\":$page,\"count\":$count}\n"
        NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null)
    }
}

@Composable
fun App() {
    var count by remember { mutableStateOf(HrState.count) }
    var page by remember { mutableStateOf(HrState.page) }
    LaunchedEffect(page, count) { HrState.save(page, count) }
    val tick = remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick.value++
        }
    }
    println("RENDER_APP $APP_MARK tick=${tick.value} count=$count page=$page APPKMP[${appCommon()} ${appPlatform()}]")
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF101820)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (page == 0) {
                Text("APP $APP_MARK", color = Color.White, fontSize = 24.sp)
                Text("from ui module:", color = Color.White, fontSize = 14.sp)
                UiMark(tick.value)
                UiMark2(tick.value)
                Spacer(Modifier.height(16.dp))
                Text("Counter: $count", color = Color.White, fontSize = 32.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { count-- }) { Text("-") }
                    Button(onClick = { count++ }) { Text("+") }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { page = 1 }) { Text("Next") }
            } else {
                Text("Page 2", color = Color.White, fontSize = 28.sp)
                Text("APP $APP_MARK", color = Color(0xFF8AB4F8), fontSize = 16.sp)
                UiMark(tick.value)
                UiMark2(tick.value)
                Text("Counter kept: $count", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { page = 0 }) { Text("Back") }
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
        w.rootViewController = ComposeUIViewController { App() }  // Compose's strict Info.plist check stays on (fixed in toolchain .5)
        w.makeKeyAndVisible()
        _window = w
        return true
    }
}

// Regression probe: throw through several JIT-compiled frames and catch it.
// Without a registered __eh_frame for JIT code (KALDO_SYNTH_EH_FRAME=1 plus
// KALDO_KEEP_COMPACT_UNWIND=1 at build time) the unwinder cannot walk these
// frames and the process terminates here. run.sh requires "EH OK" on every start.
private fun ehProbe(depth: Int): Int {
    if (depth == 0) throw RuntimeException("eh-probe")
    return ehProbe(depth - 1) + 1
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun main(args: Array<String>) {
    println("APP MAIN args=${args.size}")
    HrState.restore(args)
    try { ehProbe(6); println("EH FAIL no-throw") } catch (e: RuntimeException) { println("EH OK " + e.message) }
    kotlin.native.setUnhandledExceptionHook { e ->
        println("HOOK class=" + (e::class.simpleName ?: "?"))
        println("HOOK msg=" + (e.message ?: "<null>"))
        try { println("HOOK trace=" + e.stackTraceToString()) } catch (t: Throwable) {}
    }
    memScoped {
        val argc = args.size + 1
        val argv = (arrayOf("ComposeHR") + args).map { it.cstr.ptr }.toCValues()
        autoreleasepool {
            UIApplicationMain(argc, argv, null, NSStringFromClass(AppDelegate))
        }
    }
}
