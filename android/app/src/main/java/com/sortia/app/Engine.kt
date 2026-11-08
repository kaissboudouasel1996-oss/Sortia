package com.sortia.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Categories
// ---------------------------------------------------------------------------------------------

enum class Cat(val label: String, val folder: String, val exts: Set<String>) {
    IMAGES(
        "Images", "Images",
        setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "raw", "svg", "tiff"),
    ),
    VIDEOS(
        "Videos", "Videos",
        setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "flv", "wmv", "mpg", "mpeg"),
    ),
    AUDIO(
        "Audio", "Audio",
        setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "mid"),
    ),
    DOCUMENTS(
        "Documents", "Documents",
        setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp",
            "csv", "epub", "md", "json", "xml", "html", "htm",
        ),
    ),
    ARCHIVES("Archives", "Archives", setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")),
    APPS("Apps", "Apps", setOf("apk", "xapk", "apks", "aab")),
    OTHER("Other", "Other", emptySet());

    companion object {
        fun of(file: File): Cat {
            val ext = file.extension.lowercase()
            return values().firstOrNull { ext in it.exts } ?: OTHER
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

/** Where to look. The key is stored; the folder is resolved at scan time. */
enum class Source(val label: String, val hint: String, val defaultOn: Boolean) {
    DOWNLOADS("Downloads", "Everything you downloaded", true),
    DOCUMENTS("Documents", "Your Documents folder", true),
    PICTURES("Pictures", "Screenshots and saved pictures", true),
    MOVIES("Movies", "Your Movies folder", true),
    MUSIC("Music", "Your Music folder", true),
    LOOSE("Loose files", "Files lying around the main storage folder", true),
    CAMERA("Camera (DCIM)", "Photos and videos from your camera. Off by default: moving them can stop cloud backup apps from seeing them.", false),
}

enum class AutoTidy(val label: String, val hours: Long) {
    OFF("Off", 0),
    DAILY("Daily", 24),
    WEEKLY("Weekly", 24 * 7),
}

data class Options(
    val sources: Set<Source>,
    val byDate: Boolean,
    val removeEmpty: Boolean,
    val skipPreview: Boolean,
    val autoTidy: AutoTidy,
)

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("sortia", Context.MODE_PRIVATE)

    fun load(c: Context): Options {
        val s = sp(c)
        val saved = s.getStringSet("sources", null)
        val sources = if (saved == null) {
            Source.entries.filter { it.defaultOn }.toSet()
        } else {
            Source.entries.filter { it.name in saved }.toSet()
        }
        val tidy = AutoTidy.entries.firstOrNull { it.name == s.getString("auto", "OFF") } ?: AutoTidy.OFF
        return Options(
            sources = sources,
            byDate = s.getBoolean("by_date", true),
            removeEmpty = s.getBoolean("remove_empty", true),
            skipPreview = s.getBoolean("skip_preview", false),
            autoTidy = tidy,
        )
    }

    fun save(c: Context, o: Options) {
        sp(c).edit()
            .putStringSet("sources", o.sources.map { it.name }.toSet())
            .putBoolean("by_date", o.byDate)
            .putBoolean("remove_empty", o.removeEmpty)
            .putBoolean("skip_preview", o.skipPreview)
            .putString("auto", o.autoTidy.name)
            .apply()
    }
}

// ---------------------------------------------------------------------------------------------
// Plan
// ---------------------------------------------------------------------------------------------

class Move(val from: File, val to: File, val cat: Cat, val size: Long)

class Plan(val moves: List<Move>) {
    val totalBytes: Long get() = moves.sumOf { it.size }
    fun count(cat: Cat): Int = moves.count { it.cat == cat }
    fun bytes(cat: Cat): Long = moves.filter { it.cat == cat }.sumOf { it.size }
}

class RunResult(val moved: Int, val failed: Int, val bytes: Long, val runId: String?)

object Organizer {
    /** Everything Sortia files away lives under this folder. */
    const val ROOT_NAME = "Sortia"
    const val DUP_NAME = "Duplicates"

    private val partial = setOf("crdownload", "part", "tmp", "download", "partial", "temp", "opdownload")
    private const val RECENT_MS = 90_000L // a file touched this recently may still be downloading

    fun storageRoot(): File = Environment.getExternalStorageDirectory()
    fun destRoot(): File = File(storageRoot(), ROOT_NAME)

    private fun sourceDir(s: Source): File = when (s) {
        Source.DOWNLOADS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        Source.DOCUMENTS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        Source.PICTURES -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        Source.MOVIES -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        Source.MUSIC -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        Source.CAMERA -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        Source.LOOSE -> storageRoot()
    }

    /** Every file Sortia is allowed to touch for these options. */
    fun candidates(sources: Set<Source>): List<File> {
        val dest = destRoot().canonicalPath
        val out = ArrayList<File>()

        fun walk(dir: File, depth: Int) {
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (f.canonicalPath == dest || f.name == "Android") continue
                    walk(f, depth + 1)
                } else if (f.isFile) {
                    out.add(f)
                }
            }
        }

        for (s in sources) {
            val dir = sourceDir(s)
            if (!dir.isDirectory) continue
            if (s == Source.LOOSE) {
                // Only the files directly in the main folder; its sub-folders belong to other apps.
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && !f.name.startsWith(".")) out.add(f)
                }
            } else {
                walk(dir, 0)
            }
        }
        return out.distinctBy { it.absolutePath }
    }

    private fun isSettled(f: File, now: Long): Boolean =
        f.extension.lowercase() !in partial && now - f.lastModified() > RECENT_MS && f.length() > 0

    private val nameDate = Regex("(?<!\\d)((?:19|20)\\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\\d|3[01])(?!\\d)")

    /** Year and month a media file belongs to: from its name, then its EXIF tag, then its modified date. */
    private fun yearMonth(f: File, cat: Cat): Pair<Int, Int> {
        nameDate.find(f.name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        if (cat == Cat.IMAGES && f.extension.lowercase() in setOf("jpg", "jpeg", "heic", "heif")) {
            try {
                val raw = ExifInterface(f.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                if (raw != null && raw.length >= 7) {
                    val y = raw.substring(0, 4).toInt()
                    val m = raw.substring(5, 7).toInt()
                    if (y in 1990..2100 && m in 1..12) return y to m
                }
            } catch (_: Exception) {
            }
        }
        val cal = Calendar.getInstance()
        cal.timeInMillis = f.lastModified()
        return cal.get(Calendar.YEAR) to (cal.get(Calendar.MONTH) + 1)
    }

    suspend fun plan(opts: Options): Plan = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val root = destRoot()
        val moves = ArrayList<Move>()
        for (f in candidates(opts.sources)) {
            if (!isSettled(f, now)) continue
            val cat = Cat.of(f)
            var dir = File(root, cat.folder)
            if (opts.byDate && (cat == Cat.IMAGES || cat == Cat.VIDEOS)) {
                val (y, m) = yearMonth(f, cat)
                dir = File(File(dir, y.toString()), String.format(Locale.US, "%02d", m))
            }
            // Already sitting in its final folder: nothing to do.
            if (f.parentFile?.canonicalPath == dir.canonicalPath) continue
            moves.add(Move(f, File(dir, f.name), cat, f.length()))
        }
        Plan(moves)
    }

    // -----------------------------------------------------------------------------------------
    // Moving
    // -----------------------------------------------------------------------------------------

    private fun unique(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension
        val ext = if (target.extension.isEmpty()) "" else "." + target.extension
        var i = 1
        while (true) {
            val t = File(target.parentFile, "$base ($i)$ext")
            if (!t.exists()) return t
            i++
        }
    }

    /** Fast rename; falls back to copy + delete (checked by size) when the rename is refused. */
    private fun moveFile(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = false)
            if (to.length() == from.length()) {
                from.delete()
            } else {
                to.delete()
                false
            }
        } catch (_: Exception) {
            to.delete()
            false
        }
    }

    suspend fun execute(
        ctx: Context,
        plan: Plan,
        label: String,
        removeEmpty: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RunResult = withContext(Dispatchers.IO) {
        val log = ArrayList<Pair<String, String>>()
        var failed = 0
        var bytes = 0L
        val touchedDirs = HashSet<File>()
        val total = plan.moves.size

        plan.moves.forEachIndexed { i, m ->
            try {
                if (m.from.exists()) {
                    m.to.parentFile?.mkdirs()
                    val target = unique(m.to)
                    val parent = m.from.parentFile
                    if (moveFile(m.from, target)) {
                        log.add(m.from.absolutePath to target.absolutePath)
                        bytes += m.size
                        if (parent != null) touchedDirs.add(parent)
                    } else {
                        failed++
                    }
                }
            } catch (_: Exception) {
                failed++
            }
            if (i % 10 == 0 || i == total - 1) onProgress(i + 1, total)
        }

        if (removeEmpty) removeEmptyDirs(touchedDirs)
        val runId = if (log.isNotEmpty()) History.save(ctx, label, log) else null
        scan(ctx, log.flatMap { listOf(it.first, it.second) })
        RunResult(log.size, failed, bytes, runId)
    }

    /** Deletes folders left empty by a move, climbing up, but never the standard top-level folders. */
    private fun removeEmptyDirs(dirs: Set<File>) {
        val keep = Source.entries.map { sourceDir(it).canonicalPath }.toSet() + storageRoot().canonicalPath
        for (start in dirs) {
            var d: File? = start
            while (d != null && d.canonicalPath !in keep && d.isDirectory) {
                val kids = d.listFiles()
                if (kids != null && kids.isEmpty() && d.delete()) d = d.parentFile else break
            }
        }
    }

    /** Tells Gallery and Files that these paths changed, so nothing shows up twice or goes missing. */
    fun scan(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(ctx.applicationContext, paths.toTypedArray(), null, null)
    }

    // -----------------------------------------------------------------------------------------
    // Undo
    // -----------------------------------------------------------------------------------------

    suspend fun undo(ctx: Context, runId: String): Int = withContext(Dispatchers.IO) {
        val entries = History.read(ctx, runId) ?: return@withContext 0
        var restored = 0
        val changed = ArrayList<String>()
        // Newest first, so renamed copies go back in the opposite order they were made.
        for ((from, to) in entries.reversed()) {
            val moved = File(to)
            if (!moved.exists()) continue
            val back = unique(File(from))
            back.parentFile?.mkdirs()
            if (moveFile(moved, back)) {
                restored++
                changed.add(moved.absolutePath)
                changed.add(back.absolutePath)
            }
        }
        // Tidy up folders Sortia created that are now empty.
        removeEmptyUnder(destRoot())
        History.delete(ctx, runId)
        scan(ctx, changed)
        restored
    }

    private fun removeEmptyUnder(dir: File) {
        val kids = dir.listFiles() ?: return
        for (k in kids) if (k.isDirectory) removeEmptyUnder(k)
        if (dir.listFiles()?.isEmpty() == true && dir != storageRoot()) dir.delete()
    }

    // -----------------------------------------------------------------------------------------
    // Duplicates
    // -----------------------------------------------------------------------------------------

    class DupGroup(val keep: File, val extras: List<File>) {
        val wasted: Long get() = extras.sumOf { it.length() }
    }

    class DupResult(val groups: List<DupGroup>) {
        val extraCount: Int get() = groups.sumOf { it.extras.size }
        val wasted: Long get() = groups.sumOf { it.wasted }
    }

    private fun md5(f: File): String? = try {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /** Files with identical content. The oldest copy is kept; the rest are the "extras". */
    suspend fun findDuplicates(
        opts: Options,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): DupResult = withContext(Dispatchers.IO) {
        val files = HashSet<File>(candidates(opts.sources))
        // Files Sortia already filed away count too, so a re-download of something it sorted is caught.
        destRoot().walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { files.add(it) }
        val dupDir = File(destRoot(), DUP_NAME).canonicalPath
        val pool = files.filter { it.length() >= 1024 && !it.canonicalPath.startsWith(dupDir) }

        val bySize = pool.groupBy { it.length() }.filter { it.value.size > 1 }
        val toHash = bySize.values.sumOf { it.size }
        var done = 0
        val groups = ArrayList<DupGroup>()

        for ((_, same) in bySize) {
            val byHash = HashMap<String, MutableList<File>>()
            for (f in same) {
                val h = md5(f)
                done++
                if (done % 5 == 0) onProgress(done, toHash)
                if (h != null) byHash.getOrPut(h) { ArrayList() }.add(f)
            }
            for ((_, list) in byHash) {
                if (list.size < 2) continue
                val sorted = list.sortedWith(compareBy({ it.lastModified() }, { it.absolutePath.length }))
                groups.add(DupGroup(sorted.first(), sorted.drop(1)))
            }
        }
        onProgress(toHash, toHash)
        DupResult(groups.sortedByDescending { it.wasted })
    }

    /** Deletes the extra copies for good (the kept copy of each file stays). */
    suspend fun deleteDuplicates(ctx: Context, res: DupResult): RunResult = withContext(Dispatchers.IO) {
        var n = 0
        var bytes = 0L
        val paths = ArrayList<String>()
        for (g in res.groups) {
            for (f in g.extras) {
                val size = f.length()
                if (f.delete()) {
                    n++
                    bytes += size
                    paths.add(f.absolutePath)
                }
            }
        }
        scan(ctx, paths)
        RunResult(n, res.extraCount - n, bytes, null)
    }

    /** Moves the extra copies into Sortia/Duplicates so you can look before deleting. Undo-able. */
    suspend fun filterDuplicates(ctx: Context, res: DupResult): RunResult {
        val dest = File(destRoot(), DUP_NAME)
        val moves = res.groups.flatMap { g -> g.extras.map { Move(it, File(dest, it.name), Cat.of(it), it.length()) } }
        return execute(ctx, Plan(moves), "Duplicates", removeEmpty = false)
    }
}

// ---------------------------------------------------------------------------------------------
// History (undo log)
// ---------------------------------------------------------------------------------------------

class HistoryItem(val id: String, val label: String, val time: Long, val count: Int)

object History {
    private fun dir(c: Context) = File(c.filesDir, "runs").apply { mkdirs() }

    fun save(c: Context, label: String, entries: List<Pair<String, String>>): String {
        val id = System.currentTimeMillis().toString()
        val arr = JSONArray()
        for ((from, to) in entries) arr.put(JSONObject().put("from", from).put("to", to))
        val root = JSONObject().put("label", label).put("time", id.toLong()).put("moves", arr)
        File(dir(c), "$id.json").writeText(root.toString())
        // Keep the last ten runs.
        dir(c).listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        return id
    }

    fun read(c: Context, id: String): List<Pair<String, String>>? {
        val f = File(dir(c), "$id.json")
        if (!f.exists()) return null
        val arr = JSONObject(f.readText()).getJSONArray("moves")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("from") to o.getString("to")
        }
    }

    fun delete(c: Context, id: String) {
        File(dir(c), "$id.json").delete()
    }

    fun list(c: Context): List<HistoryItem> =
        (dir(c).listFiles() ?: emptyArray()).sortedByDescending { it.name }.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                HistoryItem(f.nameWithoutExtension, o.optString("label", "Organize"), o.optLong("time"), o.getJSONArray("moves").length())
            } catch (_: Exception) {
                null
            }
        }
}

fun formatBytes(b: Long): String {
    val gb = b / 1073741824.0
    val mb = b / 1048576.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.0f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    }
}

fun formatWhen(ms: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(ms)
