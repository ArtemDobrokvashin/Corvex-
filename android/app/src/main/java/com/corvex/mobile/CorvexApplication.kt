package com.corvex.mobile

import android.app.Application
import com.corvex.mobile.data.worker.MailSyncWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CorvexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MailSyncWorker.schedule(this)
    }
}
