package com.zweichen.photoframe

import android.app.Application
import android.content.Context
import com.zweichen.photoframe.data.FrameSettings
import com.zweichen.photoframe.data.PhotoStore
import com.zweichen.photoframe.power.ForegroundGuard
import com.zweichen.photoframe.power.SleepController
import com.zweichen.photoframe.power.SleepScheduler
import com.zweichen.photoframe.transfer.TransferSession
import com.zweichen.photoframe.util.TransferLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FrameApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var store: PhotoStore
        private set
    lateinit var settings: FrameSettings
        private set
    lateinit var transfer: TransferSession
        private set
    lateinit var sleep: SleepController
        private set

    override fun onCreate() {
        super.onCreate()
        TransferLog.init(this)
        store = PhotoStore(this)
        settings = FrameSettings(this)
        transfer = TransferSession(this)
        sleep = SleepController(this)
        SleepScheduler.reschedule(this)
        settings.addListener { SleepScheduler.reschedule(this) }
        ForegroundGuard.install(this)
    }
}

val Context.frameApp: FrameApp get() = applicationContext as FrameApp
