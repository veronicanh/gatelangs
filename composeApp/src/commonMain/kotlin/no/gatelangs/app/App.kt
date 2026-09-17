package no.gatelangs.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import no.gatelangs.app.ui.MapScreen
import no.gatelangs.app.ui.theme.GatelangsTheme

@Composable
fun App() {
    GatelangsTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MapScreen()
        }
    }
}
