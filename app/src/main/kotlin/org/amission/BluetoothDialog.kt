package org.amission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Choose the flight controller's Bluetooth module, and check that it answers. */
@Composable
fun BluetoothDialog(vm: MissionViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scanner = remember { BtScanner(context) }
    val scope = rememberCoroutineScope()
    val connections = vm.connections
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (BtPermissions.has(context)) afterPermission?.invoke() else testResult = "Bluetooth access is needed to find and connect to the flight controller."
        afterPermission = null
    }

    fun withPermission(action: () -> Unit) {
        if (BtPermissions.has(context)) action() else { afterPermission = action; permission.launch(BtPermissions.needed) }
    }

    fun test(target: BtTarget) = withPermission {
        scanner.stop() // a running scan makes connecting slow and unreliable
        testing = true
        testResult = null
        scope.launch {
            testResult = connections.test(target)
            testing = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bluetooth connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Connect to the flight controller's Bluetooth module instead of a USB cable. Its UART must have MSP " +
                        "enabled (Ports tab in the Configurator). Pair the module in Android's Bluetooth settings first if it asks for a PIN.",
                    style = MaterialTheme.typography.bodySmall,
                )

                val saved = connections.bluetooth
                if (saved != null) {
                    Text("Selected: ${saved.title} (${saved.kind})", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { test(saved) }, enabled = !testing) { Text(if (testing) "Testing…" else "Test") }
                        OutlinedButton(onClick = { connections.forget(); testResult = null }, enabled = !testing) { Text("Forget") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = connections.preferBluetooth, onCheckedChange = connections::updatePreferBluetooth)
                        Text("Use Bluetooth even when a USB cable is plugged in", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("No device selected. Without one, only USB is used.", style = MaterialTheme.typography.bodyMedium)
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Button(onClick = { withPermission { scanner.start() } }, enabled = !scanner.scanning && !testing) {
                    Text(if (scanner.scanning) "Scanning…" else "Scan for devices")
                }
                scanner.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (scanner.results.isNotEmpty()) {
                    Text("Tap a device to select it and test it.", style = MaterialTheme.typography.bodySmall)
                }
                scanner.results.forEach { f ->
                    val t = f.target
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = !testing) { connections.select(t); test(t) }.padding(vertical = 6.dp),
                    ) {
                        Text(
                            t.title + if (connections.bluetooth == t) "  ✓" else "",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            listOfNotNull(
                                t.kind,
                                if (f.paired) "paired" else null,
                                f.rssi?.let { "$it dBm" },
                                if (t.name != null) t.address else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
