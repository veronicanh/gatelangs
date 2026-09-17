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
    // Dark by default rather than following the system, because the map is designed
    // around a dark basemap: unwalked road is the only bright thing on it. Swap this
    // for `isSystemInDarkTheme()` and the basemap follows along on its own.
    GatelangsTheme(darkTheme = true) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MapScreen()
        }
    }
}
