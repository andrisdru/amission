package org.amission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Licence notice, safety warning and data credits. Several of these are required by the licences involved. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("amission $version") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("An unofficial mission planner for INAV flight controllers. It is not affiliated with or endorsed by the INAV project.")
                Text("Use at your own risk", fontWeight = FontWeight.Bold)
                Text(
                    "This is flight software and it is early. Check every mission in the INAV Configurator, " +
                        "test with the props off, and keep control of the aircraft. Terrain heights are approximate.",
                )
                Text("Licence", fontWeight = FontWeight.Bold)
                Text(
                    "Free software under the GNU General Public License version 3. It comes with ABSOLUTELY NO WARRANTY; " +
                        "you may redistribute it under the terms of that licence. Source code: ${MapSdk.SOURCE_URL}",
                )
                Text("Credits", fontWeight = FontWeight.Bold)
                Text(
                    "Street map: OpenFreeMap, OpenMapTiles, map data © OpenStreetMap contributors (ODbL).\n" +
                        "Map rendering: MapLibre Native.\n" +
                        "Terrain heights: Open-Meteo.com (CC BY 4.0), derived from the Copernicus DEM.\n" +
                        "Satellite imagery: your own provider, under its own terms.\n" +
                        "USB serial: usb-serial-for-android (MIT).\n" +
                        "Mission file format and protocol behaviour follow the INAV firmware and Configurator (GPL-3.0).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
