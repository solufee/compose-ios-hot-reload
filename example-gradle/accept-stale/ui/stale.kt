package expui

// Test screens for accept-stale.py (forced recomposition after hot reload, toolchain .6).
// The script edits S_MARK and reloads; every screen shows S_MARK through call sites whose
// arguments are static, which Compose skips unless the reload forces a recomposition.
// Dialog and BackHandler need androidx.navigationevent:navigationevent-compose; toolchains up
// to .5 do not ship it and both fail with IrLinkageError at run time (LIMITATIONS 18).

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.launch

const val S_MARK = "s0"

@Composable
fun StaleAcceptApp() {
    var page by rememberSaveable { mutableStateOf(0) }
    var plain by remember { mutableStateOf(0) }
    var saved by rememberSaveable { mutableStateOf(0) }
    var popup by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    var backs by remember { mutableStateOf(0) }
    println("STALE_STATE page=$page plain=$plain saved=$saved popup=$popup dialog=$dialog backs=$backs")
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF101820)).padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { page = 0 }) { Text("Home") }
                Button(onClick = { page = 1 }) { Text("List") }
                Button(onClick = { page = 2 }) { Text("Big") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { page = 3 }) { Text("Back") }
                Button(onClick = { plain++ }) { Text("Plain+") }
                Button(onClick = { saved++ }) { Text("Saved+") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { popup = true }) { Text("Popup") }
                Button(onClick = { dialog = true }) { Text("Dialog") }
            }
            Text("plain=$plain saved=$saved page=$page", color = Color.White, fontSize = 14.sp)
            Header()
            when (page) {
                0 -> HomePage()
                1 -> SmallList()
                2 -> BigList()
                else -> BackPage(onBack = { backs++ })
            }
        }
        if (dialog) {
            Dialog(onDismissRequest = { dialog = false }) {
                DialogBody(onClose = { dialog = false })
            }
        }
        if (popup) {
            Popup(alignment = Alignment.Center, onDismissRequest = { popup = false }) {
                PopupBody(onClose = { popup = false })
            }
        }
    }
}

@Composable
fun Header() {
    println("SHOW HEADER $S_MARK")
    Text("HEADER $S_MARK", color = Color(0xFF7FD1AE), fontSize = 16.sp)
}

@Composable
fun HomePage() {
    println("SHOW HOME $S_MARK")
    Spacer(Modifier.height(24.dp))
    Text("HOME $S_MARK", color = Color.White, fontSize = 28.sp)
}

@Composable
fun SmallList() {
    LazyColumn(Modifier.fillMaxWidth().padding(8.dp)) {
        items(60) { i -> ListRow(i) }
    }
}

@Composable
fun ListRow(i: Int) {
    println("SHOW ROW$i $S_MARK")
    Text("ROW $i $S_MARK", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(6.dp))
}

@Composable
fun BigList() {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // "Scroll": benchmark for accept-stale.py --scroll-only (scrollbench.kt).
    Button(onClick = { scope.launch { scrollBenchmark(state) } }) { Text("Scroll") }
    LazyColumn(Modifier.fillMaxWidth().padding(4.dp), state = state) {
        items(600) { i -> BigRow(i) }
    }
}

@Composable
fun BigRow(i: Int) {
    println("SHOW BIG$i $S_MARK")
    // 14 layout nodes per row: the Row, the index, and 4 cells of 3 nodes.
    Row(Modifier.height(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$i", color = Color.Gray, fontSize = 8.sp, modifier = Modifier.padding(end = 3.dp))
        for (k in 0 until 4) BigCell(k)
    }
}

@Composable
fun BigCell(k: Int) {
    Box(Modifier.size(6.dp).background(if (k % 2 == 0) Color(0xFF8AB4F8) else Color(0xFF7FD1AE)))
    Text(" $S_MARK", color = Color(0xFFF4B183), fontSize = 8.sp)
    Text(" c$k ", color = Color.White, fontSize = 8.sp)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BackPage(onBack: () -> Unit) {
    // A system back (left-edge swipe) goes to this handler instead of the default.
    BackHandler(enabled = true) {
        println("BACK HANDLED $S_MARK")
        onBack()
    }
    // "SimBack" sends a back event the way the iOS edge-swipe input does:
    // an input -> the window's NavigationEventDispatcher -> the handler above.
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val input = remember { DirectNavigationEventInput() }
    DisposableEffect(dispatcher) {
        dispatcher?.addInput(input)
        onDispose { dispatcher?.removeInput(input) }
    }
    println("SHOW BACKPAGE $S_MARK")
    Spacer(Modifier.height(24.dp))
    Text("BACKPAGE $S_MARK", color = Color.White, fontSize = 24.sp)
    Button(onClick = { input.backCompleted() }) { Text("SimBack") }
}

@Composable
fun DialogBody(onClose: () -> Unit) {
    println("SHOW DIALOG $S_MARK")
    Column(
        Modifier.background(Color(0xFF3C3430)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DIALOG $S_MARK", color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose) { Text("Done") }
    }
}

@Composable
fun PopupBody(onClose: () -> Unit) {
    println("SHOW POPUP $S_MARK")
    Column(
        Modifier.background(Color(0xFF30343C)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("POPUP $S_MARK", color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose) { Text("Close") }
    }
}
