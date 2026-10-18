package org.amission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Choose where the quad will take off. Relative altitudes count from the ground there, so it decides what the
 * Profile tab calls "height above ground". It is for planning only and is not sent to the flight controller.
 */
@Composable
fun HomeDialog(vm: MissionViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (PhoneLocation.hasPermission(context)) vm.setHomeFromPhoneGps(onDismiss)
        else vm.reportStatus("Allow location access to use the phone's GPS.")
    }
    val busy = vm.homeBusy
    val home = vm.home

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Home point") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Where will the quad take off? Relative altitudes are measured from the ground there, so this decides " +
                        "the heights in the Profile tab. It is only for planning; the quad sets its own home when it arms.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (home == null) "No home set. Point 1 is assumed to be at take-off height."
                    else String.format(Locale.US, "Home: %.6f, %.6f", home.first, home.second),
                    style = MaterialTheme.typography.titleSmall,
                )
                Button(onClick = { vm.setHomeFromQuadGps(onDismiss) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Use the quad's GPS")
                }
                Button(
                    onClick = {
                        if (PhoneLocation.hasPermission(context)) vm.setHomeFromPhoneGps(onDismiss)
                        else permission.launch(PhoneLocation.permissions)
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use the phone's GPS") }
                OutlinedButton(
                    onClick = { if (vm.setHomeAtMapCenter()) onDismiss() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use the centre of the map") }
                OutlinedButton(onClick = { vm.clearHome(); onDismiss() }, enabled = !busy && home != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear home")
                }
                Text(if (busy) "Working…" else vm.status, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}
