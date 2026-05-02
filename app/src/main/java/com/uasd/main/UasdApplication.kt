package com.uasd.main

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

class UasdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Habilitar persistencia de datos sin conexión
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)
    }
}
