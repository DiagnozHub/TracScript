package com.brain.tracscript.security

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Диалог запроса пароля при попытке выполнить защищённое действие.
 *
 * Если пароль НЕ задан — диалог сразу не показываем (вызывающая сторона уже
 * проверила это через [AdminAuth.isOpenAccess]/[rememberAdminGate]).
 */
@Composable
fun PasswordPromptDialog(
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Пароль администратора",
    message: String = "Введите пароль, чтобы продолжить."
) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text("Пароль") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (AdminAuth.verifyPassword(ctx, input)) {
                    AdminAuth.markUnlocked()
                    onSuccess()
                } else {
                    Toast.makeText(ctx, "Неверный пароль", Toast.LENGTH_SHORT).show()
                    input = ""
                }
            }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

/**
 * Диалог установки/смены/снятия пароля. Показывает поля в зависимости от того,
 * задан пароль сейчас или нет. Пустой новый пароль = снять защиту.
 */
@Composable
fun PasswordSetupDialog(
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val passwordSet = remember { mutableStateOf(AdminAuth.isPasswordSet(ctx)) }

    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var newPwdRepeat by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (passwordSet.value) "Изменить пароль" else "Задать пароль") },
        text = {
            Column {
                if (passwordSet.value) {
                    OutlinedTextField(
                        value = oldPwd,
                        onValueChange = { oldPwd = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        label = { Text("Текущий пароль") }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = newPwd,
                    onValueChange = { newPwd = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = {
                        Text(if (passwordSet.value) "Новый пароль (пусто — снять защиту)" else "Новый пароль")
                    }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPwdRepeat,
                    onValueChange = { newPwdRepeat = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text("Повторите новый пароль") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 1) Если пароль уже задан — проверяем старый
                if (passwordSet.value && !AdminAuth.verifyPassword(ctx, oldPwd)) {
                    Toast.makeText(ctx, "Неверный текущий пароль", Toast.LENGTH_SHORT).show()
                    return@TextButton
                }
                // 2) Сравниваем новый с подтверждением
                if (newPwd != newPwdRepeat) {
                    Toast.makeText(ctx, "Пароли не совпадают", Toast.LENGTH_SHORT).show()
                    return@TextButton
                }
                // 3) Применяем
                AdminAuth.setPassword(ctx, newPwd)
                val msg = if (newPwd.isBlank()) "Защита снята" else "Пароль установлен"
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                onClose()
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Отмена") }
        }
    )
}

/**
 * Composable-«шлюз»: возвращает функцию-инвокер для защищённых действий.
 *
 *   val gate = rememberAdminGate()
 *   Button(onClick = { gate { openSettings() } })
 *
 * Если защита не нужна (пароль не задан или сессия уже разблокирована) — действие
 * выполняется сразу. Иначе показывается PasswordPromptDialog, и при успешном
 * вводе действие выполняется.
 */
@Composable
fun rememberAdminGate(): (action: () -> Unit) -> Unit {
    val ctx = LocalContext.current
    val unlocked by AdminAuth.sessionUnlocked.collectAsState()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    if (pending != null) {
        PasswordPromptDialog(
            onSuccess = {
                val a = pending
                pending = null
                a?.invoke()
            },
            onDismiss = { pending = null }
        )
    }

    return remember(unlocked) {
        { action ->
            if (!AdminAuth.isPasswordSet(ctx) || unlocked) {
                action()
            } else {
                pending = action
            }
        }
    }
}
