package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.utils.BatteryUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.TASK_CONDITION_BATTERY
import cn.ppps.forwarder.utils.TASK_CONDITION_CHARGE
import cn.ppps.forwarder.utils.TaskWorker
import cn.ppps.forwarder.utils.task.TaskUtils
import cn.ppps.forwarder.workers.BatteryWorker

@Suppress("PrivatePropertyName")
class BatteryReceiver : BroadcastReceiver() {

    private val TAG: String = BatteryReceiver::class.java.simpleName

    override fun onReceive(context: Context?, intent: Intent?) {

        if (context == null || intent?.action != Intent.ACTION_BATTERY_CHANGED) return

        val hasBatteryTask = try {
            Core.task.hasByType(TASK_CONDITION_BATTERY)
        } catch (e: Exception) {
            Log.e(TAG, "query battery task failed: ${e.message}")
            false
        }
        val hasChargeTask = try {
            Core.task.hasByType(TASK_CONDITION_CHARGE)
        } catch (e: Exception) {
            Log.e(TAG, "query charge task failed: ${e.message}")
            false
        }

        val batteryInfo = BatteryUtils.getBatteryInfo(intent).toString()
        TaskUtils.batteryInfo = batteryInfo

        val levelNew = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val levelOld = TaskUtils.batteryLevel
        val isLevelChanged = levelNew != levelOld
        TaskUtils.batteryLevel = levelNew

        val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        TaskUtils.batteryPct = levelNew.toFloat() / scale.toFloat() * 100

        val pluggedNew: Int = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val pluggedOld = TaskUtils.batteryPlugged
        val isPluggedChanged = pluggedNew != pluggedOld
        TaskUtils.batteryPlugged = pluggedNew

        val statusNew: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val statusOld = TaskUtils.batteryStatus
        val isStatusChanged = statusNew != statusOld
        TaskUtils.batteryStatus = statusNew

        //电量改变
        if (isLevelChanged && hasBatteryTask) {
            Log.d(TAG, "电量改变")
            val request = OneTimeWorkRequestBuilder<BatteryWorker>().setInputData(
                workDataOf(
                    TaskWorker.CONDITION_TYPE to TASK_CONDITION_BATTERY,
                    "status" to statusNew,
                    "level_new" to levelNew,
                    "level_old" to levelOld,
                )
            ).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "battery_level_changed",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        //充电状态改变
        if ((isPluggedChanged || isStatusChanged) && hasChargeTask) {
            Log.d(TAG, "充电状态改变")
            val inputData = workDataOf(
                TaskWorker.CONDITION_TYPE to TASK_CONDITION_CHARGE,
                "status_new" to statusNew,
                "status_old" to statusOld,
                "plugged_new" to pluggedNew,
                "plugged_old" to pluggedOld,
            )
            val request = OneTimeWorkRequestBuilder<BatteryWorker>()
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "battery_charge_changed",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

    }

}
