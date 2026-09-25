package org.olcbox.app.ui.components

import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.action_cancel
import multiplatform_app.sharedui.generated.resources.admin_enter_code
import multiplatform_app.sharedui.generated.resources.admin_incorrect
import multiplatform_app.sharedui.generated.resources.admin_unlock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * Admin unlock dialog. [onSubmit] returns true when the password is correct
 * (dialog dismisses); false shows an inline error without closing.
 */
@Composable
fun AdminPasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Boolean,
) {
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.admin_enter_code)) },
        text = {
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it; error = false },
                singleLine = true,
                isError = error,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = if (error) ({ Text(stringResource(Res.string.admin_incorrect)) }) else null,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (onSubmit(pw)) onDismiss() else error = true }) {
                Text(stringResource(Res.string.admin_unlock))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
