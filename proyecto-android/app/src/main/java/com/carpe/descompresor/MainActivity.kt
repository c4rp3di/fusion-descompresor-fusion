package com.carpe.descompresor

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.carpe.descompresor.AppLogic

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppLogic.onIniciar(this)
        // Solo en un arranque real: tras girar la pantalla no se vuelve a procesar el mismo intent.
        if (savedInstanceState == null) AppLogic.manejarIntent(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLogic.manejarIntent(this, intent)   // app ya abierta (singleTop)
    }
}
