package com.trinetrax.attendance

import android.app.Application
import com.trinetrax.attendance.core.NetworkModule
import com.trinetrax.attendance.core.SessionManager

class TrinetraXApp : Application() {

    lateinit var session: SessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = SessionManager(this)
        NetworkModule.init(session)
    }

    companion object {
        lateinit var instance: TrinetraXApp
            private set
    }
}
