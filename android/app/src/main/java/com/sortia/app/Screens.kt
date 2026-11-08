package com.sortia.app

import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class Screen { HOME, FOLDERS, DUPES, HISTORY }

private sealed interface Phase {
    object Idle : Phase
    object Scanning : Phase
    class Preview(val plan: Plan) : Phase
    class Running(val done: Int, val total: Int) : Phase
    class Done(val res: RunResult) : Phase
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

@Composable
private fun Header(title: String, sub: String? = null, onBack: (() -> Unit)? = null) {
    Column(Modifier.padding(bottom = 8.dp)) {
        if (onBack != null) {
            Text(
                "< Back",
                color = P.Teal,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onBack() }.padding(vertical = 8.dp),
            )
        }
        Text(title, style = TextStyle(brush = P.Grad, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold))
        if (sub != null) Muted(sub)
    }
}

// ---------------------------------------------------------------------------------------------
// Home: the one-tap organizer
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(
    opts: Options,
    hasAccess: Boolean,
    onGrant: () -> Unit,
    go: (Screen) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var history by remember { mutableStateOf(History.list(ctx)) }
    val view = LocalView.current

    val p = phase
    val busy = p is Phase.Scanning || p is Phase.Running
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    fun runPlan(plan: Plan) {
        scope.launch {
            phase = Phase.Running(0, plan.moves.size)
            val res = Organizer.execute(ctx, plan, "Organize", opts.removeEmpty) { d, t -> phase = Phase.Running(d, t) }
            history = History.list(ctx)
            phase = Phase.Done(res)
        }
    }

    fun start() {
        scope.launch {
            phase = Phase.Scanning
            val plan = Organizer.plan(opts)
            if (plan.moves.isEmpty()) {
                phase = Phase.Done(RunResult(0, 0, 0, null))
            } else if (opts.skipPreview) {
                runPlan(plan)
            } else {
                phase = Phase.Preview(plan)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header("Sortia", "Your files, in order. One tap.")

        if (!hasAccess) {
            SCard {
                Text("Allow file access", color = P.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Muted(
                    "To move your files into tidy folders Sortia needs \"All files access\". " +
                        "It works only on your phone. Nothing is uploaded and nothing is deleted.",
                )
                Spacer(Modifier.height(16.dp))
                GradientButton("Allow access", onClick = onGrant)
            }
            return@Column
        }

        SCard {
            val st = remember(p) { storage() }
            Text("PHONE STORAGE", color = P.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatBytes(st.first)} used of ${formatBytes(st.second)}",
                color = P.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Bar(st.first.toFloat() / st.second.coerceAtLeast(1))
            Spacer(Modifier.height(20.dp))

            when (p) {
                is Phase.Idle -> {
                    Muted(
                        "Sorts ${opts.sources.size} places into Sortia/Images, Videos, Audio, Documents, Archives, " +
                            "Apps and Other" + if (opts.byDate) ", with photos and videos by year and month." else ".",
                    )
                    Spacer(Modifier.height(16.dp))
                    GradientButton("Organize now", enabled = opts.sources.isNotEmpty()) { start() }
                    if (opts.sources.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Muted("Pick at least one folder in Folders and options.")
                    }
                }

                is Phase.Scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = P.Teal, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Looking at your files...", color = P.Text, fontSize = 17.sp)
                }

                is Phase.Preview -> {
                    val plan = p.plan
                    Text(
                        "${plan.moves.size} files (${formatBytes(plan.totalBytes)}) are ready",
                        color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    val most = Cat.values().maxOf { plan.count(it) }.coerceAtLeast(1)
                    Cat.values().filter { plan.count(it) > 0 }.forEach {
                        CatRow(it, plan.count(it), plan.bytes(it), plan.count(it).toFloat() / most)
                    }
                    Spacer(Modifier.height(16.dp))
                    GradientButton("Organize ${plan.moves.size} files") { runPlan(plan) }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Cancel") { phase = Phase.Idle }
                    Spacer(Modifier.height(8.dp))
                    Muted("You can undo this afterwards.")
                }

                is Phase.Running -> {
                    Text("Organizing... ${p.done} of ${p.total}", color = P.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Bar(p.done.toFloat() / p.total.coerceAtLeast(1))
                    Spacer(Modifier.height(8.dp))
                    Muted("Keep Sortia open until it finishes.")
                }

                is Phase.Done -> {
                    val r = p.res
                    if (r.moved == 0 && r.failed == 0) {
                        Text("Everything is already in order.", color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Muted("Nothing new to sort.")
                    } else {
                        Text(
                            "Done. ${r.moved} files (${formatBytes(r.bytes)}) sorted.",
                            color = P.Teal, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        )
                        if (r.failed > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text("${r.failed} could not be moved and were left where they are.", color = P.Err, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Muted("Find them in the Sortia folder on your phone.")
                    }
                    Spacer(Modifier.height(16.dp))
                    GradientButton("Great") { phase = Phase.Idle }
                    if (r.runId != null) {
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("Undo") {
                            scope.launch {
                                val n = Organizer.undo(ctx, r.runId)
                                history = History.list(ctx)
                                toast(ctx, "$n files put back")
                                phase = Phase.Idle
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Find duplicates", "Free up space", Modifier.weight(1f)) { go(Screen.DUPES) }
            Tile("History", if (history.isEmpty()) "Nothing yet" else "${history.size} runs, undo any", Modifier.weight(1f)) { go(Screen.HISTORY) }
        }
        Tile(
            "Folders and options",
            "Auto-tidy: ${opts.autoTidy.label}   |   ${opts.sources.size} places",
            Modifier.fillMaxWidth(),
        ) { go(Screen.FOLDERS) }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Tile(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .background(P.Card, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Text(title, color = P.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Muted(sub)
    }
}

private fun storage(): Pair<Long, Long> {
    val st = StatFs(Environment.getExternalStorageDirectory().path)
    val total = st.totalBytes
    return (total - st.availableBytes) to total
}

// ---------------------------------------------------------------------------------------------
// Folders and options
// ---------------------------------------------------------------------------------------------

@Composable
fun FoldersScreen(opts: Options, onChange: (Options) -> Unit, askNotifications: () -> Unit, back: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header("Folders", "Choose what Sortia looks at.", back)

        SCard {
            Source.values().forEach { s ->
                SwitchRow(s.label, s.hint, s in opts.sources) { on ->
                    onChange(opts.copy(sources = if (on) opts.sources + s else opts.sources - s))
                }
            }
        }

        SCard {
            Text("OPTIONS", color = P.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            SwitchRow("Photos and videos by month", "Images/2026/11 instead of one big pile.", opts.byDate) {
                onChange(opts.copy(byDate = it))
            }
            SwitchRow("Remove empty folders", "Clean up folders left empty after moving.", opts.removeEmpty) {
                onChange(opts.copy(removeEmpty = it))
            }
            SwitchRow(
                "Skip the preview",
                "Organize instantly when you tap the button. You can still undo.",
                opts.skipPreview,
            ) { onChange(opts.copy(skipPreview = it)) }
        }

        SCard {
            Text("AUTO-TIDY", color = P.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(4.dp))
            Muted("Organizes in the background so new downloads never pile up, and tells you what it did.")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AutoTidy.values().forEach { m ->
                    ChoiceChip(m.label, opts.autoTidy == m) {
                        if (m != AutoTidy.OFF) askNotifications()
                        onChange(opts.copy(autoTidy = m))
                    }
                }
            }
        }

        SCard {
            Text("SAFE BY DESIGN", color = P.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(4.dp))
            Muted(
                "Sortia never touches app data, hidden files or files still downloading, never overwrites a file " +
                    "(a copy gets \"(1)\" added), and keeps a log of every move so you can undo it.",
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Duplicates
// ---------------------------------------------------------------------------------------------

@Composable
fun DuplicatesScreen(opts: Options, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<Organizer.DupResult?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun scan() {
        scope.launch {
            scanning = true
            progress = 0f
            result = Organizer.findDuplicates(opts) { d, t -> progress = d.toFloat() / t.coerceAtLeast(1) }
            scanning = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header("Duplicates", "Find identical files and free up space.", back)

        SCard {
            val r = result
            when {
                scanning -> {
                    Text("Comparing files...", color = P.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Bar(progress)
                }

                r == null -> {
                    Muted("Looks inside your chosen folders and the Sortia folder, and compares what is really inside each file, not just its name.")
                    Spacer(Modifier.height(16.dp))
                    GradientButton("Scan for duplicates") { scan() }
                }

                r.extraCount == 0 -> {
                    Text("No duplicates found.", color = P.Teal, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton("Scan again") { scan() }
                }

                else -> {
                    Text(
                        "${r.extraCount} extra copies, ${formatBytes(r.wasted)} wasted",
                        color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Muted("The oldest copy of each file stays where it is.")
                    Spacer(Modifier.height(10.dp))
                    r.groups.take(20).forEach { g ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(g.keep.name, color = P.Text, fontSize = 15.sp, maxLines = 1)
                            Muted("${g.extras.size} more copies, ${formatBytes(g.wasted)}")
                        }
                    }
                    if (r.groups.size > 20) Muted("and ${r.groups.size - 20} more files...")
                    Spacer(Modifier.height(16.dp))
                    GradientButton("Move extras to Sortia/Duplicates") {
                        scope.launch {
                            val res = Organizer.filterDuplicates(ctx, r)
                            toast(ctx, "${res.moved} copies moved. You can undo it in History.")
                            result = null
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Delete extras for good") { confirmDelete = true }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    val r = result
    if (confirmDelete && r != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${r.extraCount} copies?") },
            text = { Text("This frees ${formatBytes(r.wasted)} and cannot be undone. One copy of each file stays.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val res = Organizer.deleteDuplicates(ctx, r)
                        toast(ctx, "${res.moved} copies deleted, ${formatBytes(res.bytes)} freed")
                        result = null
                    }
                }) { Text("Delete", color = P.Err) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------------

@Composable
fun HistoryScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(History.list(ctx)) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header("History", "Every run can be undone.", back)

        if (items.isEmpty()) {
            SCard { Muted("Nothing yet. Tap Organize now on the home screen.") }
        }
        items.forEach { item ->
            SCard {
                Text(item.label, color = P.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Muted("${item.count} files   |   ${formatWhen(item.time)}")
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Undo this run") {
                    scope.launch {
                        val n = Organizer.undo(ctx, item.id)
                        items = History.list(ctx)
                        toast(ctx, "$n files put back")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
