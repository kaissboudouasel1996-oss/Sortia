package com.sortia.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object Perms {
    /** Can Sortia move files on shared storage right now? */
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

/** Runs the same organizer as the big button, in the background, on a schedule. */
class TidyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Perms.hasAccess(ctx)) return Result.success()

        val opts = Store.load(ctx)
        val plan = Organizer.plan(opts)
        if (plan.moves.isEmpty()) return Result.success()

        val res = Organizer.execute(ctx, plan, "Auto-tidy", opts.removeEmpty)
        if (res.moved > 0) notifyDone(ctx, res)
        return Result.success()
    }

    private fun notifyDone(ctx: Context, res: RunResult) {
        if (!Perms.canNotify(ctx)) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Auto-tidy", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setContentTitle("Sortia tidied up")
            .setContentText("${res.moved} files (${formatBytes(res.bytes)}) put in order. Open Sortia to undo.")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(1, n)
    }

    companion object {
        const val CHANNEL = "auto_tidy"
    }
}

object AutoTidyScheduler {
    private const val NAME = "auto_tidy"

    fun apply(ctx: Context, mode: AutoTidy) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (mode == AutoTidy.OFF) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<TidyWorker>(mode.hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
