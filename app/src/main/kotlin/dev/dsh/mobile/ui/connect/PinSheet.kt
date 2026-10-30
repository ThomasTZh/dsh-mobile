package dev.dsh.mobile.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.net.PinRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSheet(
    busy: Boolean,
    error: String?,
    lockoutSeconds: Long,
    onDismiss: () -> Unit,
    onSubmit: (String, Boolean) -> Unit,
    onUseWebLogin: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pin by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var visible by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text("Unlock DSH", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                "Enter the LAN PIN shown in DSH on your PC under Settings → Phone access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 16.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    pin = it.take(PinRules.MAX_LENGTH)
                    localError = null
                },
                label = { Text("LAN PIN") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    val problem = PinRules.validate(pin)
                    if (problem != null) localError = problem
                    else if (pin.isNotBlank() && !busy && lockoutSeconds <= 0) onSubmit(pin, remember)
                }),
                trailingIcon = {
                    TextButton(onClick = { visible = !visible }) {
                        Text(if (visible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val shownError = localError ?: error
            if (shownError != null) {
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    shownError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (lockoutSeconds > 0) {
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    "Too many attempts. Try again in ${lockoutSeconds}s.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.padding(top = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = remember, onCheckedChange = { remember = it })
                Column {
                    Text("Remember PIN", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Encrypted on this phone; used to reconnect and for alerts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.padding(top = 14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onUseWebLogin != null) {
                    TextButton(onClick = onUseWebLogin) { Text("Use web login") }
                    Spacer(Modifier.width(6.dp))
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        val problem = PinRules.validate(pin)
                        if (problem != null) localError = problem
                        else onSubmit(pin.trim(), remember)
                    },
                    enabled = pin.isNotBlank() && !busy && lockoutSeconds <= 0,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text("Unlock")
                }
            }
        }
    }
}
