package ai.victorious.filemanager.appmgr

import ai.victorious.filemanager.core.appmgr.AppProfile
import ai.victorious.filemanager.core.appmgr.ProfileCodec
import ai.victorious.filemanager.core.appmgr.ProfileSchedule
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Runs a saved App Manager profile on a schedule (off by default). Confirmation is skipped for
 * scheduled runs — only enable schedules you trust. Shell still goes through [AppManagerService].
 */
class ProfileScheduleWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val file = File(applicationContext.filesDir, "app_manager_profiles.json")
        val profile = ProfileCodec.decode(runCatching { file.readText() }.getOrDefault(""))
            .firstOrNull { it.id == id } ?: return Result.failure()
        val app = applicationContext as ai.victorious.filemanager.FileManagerApplication
        val svc = AppManagerService(app, app.privilege)
        val plan = svc.planProfile(profile)
        val err = svc.execute(plan)
        return if (err == null) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_ID = "profile_id"
        private fun unique(id: String) = "app_profile_schedule_$id"

        fun cancel(context: Context, profileId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(unique(profileId))
        }

        fun apply(context: Context, profile: AppProfile) {
            val wm = WorkManager.getInstance(context)
            val name = unique(profile.id)
            wm.cancelUniqueWork(name)
            val sched = profile.schedule
            when (sched.mode) {
                "off", "" -> return
                "once" -> {
                    val req = OneTimeWorkRequestBuilder<ProfileScheduleWorker>()
                        .setInitialDelay(sched.delayHours.coerceAtLeast(0).toLong(), TimeUnit.HOURS)
                        .setInputData(workDataOf(KEY_ID to profile.id))
                        .build()
                    wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
                }
                "daily" -> {
                    val delay = millisUntil(sched.hourOfDay, sched.minuteOfHour)
                    val req = PeriodicWorkRequestBuilder<ProfileScheduleWorker>(24, TimeUnit.HOURS)
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(KEY_ID to profile.id))
                        .build()
                    wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
                }
                "weekly" -> {
                    val delay = millisUntilWeekday(sched.dayOfWeek, sched.hourOfDay, sched.minuteOfHour)
                    val req = PeriodicWorkRequestBuilder<ProfileScheduleWorker>(7, TimeUnit.DAYS)
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(KEY_ID to profile.id))
                        .build()
                    wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
                }
            }
        }

        private fun millisUntil(hour: Int, minute: Int): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }

        private fun millisUntilWeekday(isoDow: Int, hour: Int, minute: Int): Long {
            // Calendar: Sunday=1 … our ISO Monday=1 → Sunday=7
            val calDow = if (isoDow == 7) Calendar.SUNDAY else isoDow + 1
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                set(Calendar.DAY_OF_WEEK, calDow)
                if (before(now)) add(Calendar.WEEK_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }

        fun describe(s: ProfileSchedule): String = when (s.mode) {
            "once" -> "Once in ${s.delayHours}h"
            "daily" -> "Daily at %02d:%02d".format(s.hourOfDay, s.minuteOfHour)
            "weekly" -> "Weekly (ISO dow ${s.dayOfWeek}) at %02d:%02d".format(s.hourOfDay, s.minuteOfHour)
            else -> "Off"
        }
    }
}
