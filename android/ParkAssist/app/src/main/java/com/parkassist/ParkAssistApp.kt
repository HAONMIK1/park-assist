package com.parkassist

import android.app.Application
import android.content.Context
import com.parkassist.di.AppContainer

class ParkAssistApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as ParkAssistApp).container
