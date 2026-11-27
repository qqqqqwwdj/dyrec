package com.dsh.dyrec

import android.app.Application
import com.dsh.dyrec.data.TaskRepo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TaskRepo.init(this)
    }
}
