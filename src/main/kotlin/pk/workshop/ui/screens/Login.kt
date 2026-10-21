package pk.workshop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pk.workshop.db.AppError
import pk.workshop.security.Vault
import pk.workshop.ui.Field

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    var mode by remember { mutableStateOf(if (Vault.isSetUp()) "login" else "setup") }
    var user by remember { mutableStateOf(if (Vault.isSetUp()) Vault.username() else "admin") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var newCode by remember { mutableStateOf<String?>(null) }

    fun attempt(block: () -> Unit) {
        err = null; busy = true
        try { block() } catch (e: AppError) { err = e.message } catch (e: Exception) { err = "Could not open the data: ${e.message}" }
        busy = false
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(440.dp)) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CarRepair, null, tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Workshop Manager", style = MaterialTheme.typography.titleLarge)
                        Text(when (mode) { "setup" -> "Create the admin login"; "reset" -> "Reset password with recovery code"; else -> "Log in" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (newCode != null) {
                    Text("Write down this recovery code and keep it somewhere safe outside the shop. It is the only way back in if the password is forgotten, and it is shown only once.",
                        style = MaterialTheme.typography.bodyMedium)
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                        SelectionContainer { Text(newCode!!, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
                    }
                    Button({ onLoggedIn() }, Modifier.fillMaxWidth()) { Text("I have written it down — continue") }
                    return@Column
                }
                when (mode) {
                    "setup" -> {
                        Field("Username", user, { user = it }, Modifier.fillMaxWidth())
                        Field("Password (at least 6 characters)", pass, { pass = it }, Modifier.fillMaxWidth(), password = true)
                        Field("Repeat password", pass2, { pass2 = it }, Modifier.fillMaxWidth(), password = true, onEnter = {})
                        Button({ attempt { if (pass != pass2) throw AppError("The two passwords do not match."); newCode = Vault.setup(user, pass) } },
                            Modifier.fillMaxWidth(), enabled = !busy) { Text("Create and open") }
                    }
                    "login" -> {
                        Field("Username", user, { user = it }, Modifier.fillMaxWidth())
                        Field("Password", pass, { pass = it }, Modifier.fillMaxWidth(), password = true, onEnter = { attempt { Vault.login(user, pass); onLoggedIn() } })
                        Button({ attempt { Vault.login(user, pass); onLoggedIn() } }, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Opening…" else "Log in") }
                        TextButton({ mode = "reset"; err = null }) { Text("Forgot password?") }
                    }
                    "reset" -> {
                        Field("Recovery code", code, { code = it }, Modifier.fillMaxWidth(), placeholder = "XXXX-XXXX-XXXX-XXXX-XXXX")
                        Field("New password", pass, { pass = it }, Modifier.fillMaxWidth(), password = true)
                        Field("Repeat new password", pass2, { pass2 = it }, Modifier.fillMaxWidth(), password = true)
                        Button({ attempt { if (pass != pass2) throw AppError("The two passwords do not match."); Vault.resetPassword(code, pass); onLoggedIn() } },
                            Modifier.fillMaxWidth(), enabled = !busy) { Text("Reset and log in") }
                        TextButton({ mode = "login"; err = null }) { Text("Back to log in") }
                    }
                }
                if (err != null) Text(err!!, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
