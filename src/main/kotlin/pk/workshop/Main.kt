package pk.workshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import pk.workshop.core.Money
import pk.workshop.data.CashDrawer
import pk.workshop.data.Defaults
import pk.workshop.data.Settings
import pk.workshop.db.Db
import pk.workshop.io.Backup
import pk.workshop.print.Printers
import pk.workshop.ui.*
import pk.workshop.ui.screens.*

fun main() {
    try { Demo.maybeStart() } catch (e: Exception) { e.printStackTrace() }
    app()
}

fun app() = application {
    val state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1360.dp, 860.dp))
    Window(
        onCloseRequest = { try { if (Db.isOpen) { Backup.autoIfDue(); Db.close() } } catch (_: Exception) {}; exitApplication() },
        state = state, title = "Workshop Manager", icon = androidx.compose.ui.res.painterResource("icon.png"),
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && App.loggedIn) when (e.key) {
                Key.F2 -> { App.go(Screen.Jobs); true }
                Key.F3 -> { App.go(Screen.CounterSale()); true }
                Key.F4 -> { App.go(Screen.Customers); true }
                Key.F5 -> { App.go(Screen.Inventory); true }
                Key.F6 -> { App.go(Screen.Billing); true }
                else -> false
            } else false
        },
    ) {
        window.minimumSize = java.awt.Dimension(1100, 700)
        AppTheme(App.theme) {
            Surface(Modifier.fillMaxSize().carbonBackground(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
                if (!App.loggedIn) LoginScreen(onLoggedIn = ::afterLogin) else Shell()
            }
        }
    }
}

fun afterLogin() {
    Defaults.ensure()
    Money.lakhGrouping = Settings.bool(Settings.LAKH)
    App.theme = Settings.get(Settings.THEME, "system")
    CashDrawer.opener = { Printers.openDrawer() }
    App.loggedIn = true
    Thread { try { Backup.autoIfDue() } catch (_: Exception) {} }.start()
}

private data class NavItem(val label: String, val icon: ImageVector, val screen: Screen, val key: String = "")

private val nav = listOf(
    NavItem("Dashboard", Icons.Default.Dashboard, Screen.Dashboard),
    NavItem("Job cards", Icons.Default.Build, Screen.Jobs, "F2"),
    NavItem("Bookings", Icons.Default.EventAvailable, Screen.Bookings),
    NavItem("Counter sale", Icons.Default.PointOfSale, Screen.CounterSale(), "F3"),
    NavItem("Billing", Icons.Default.ReceiptLong, Screen.Billing, "F6"),
    NavItem("Customers", Icons.Default.People, Screen.Customers, "F4"),
    NavItem("Inventory", Icons.Default.Inventory2, Screen.Inventory, "F5"),
    NavItem("Purchasing", Icons.Default.LocalShipping, Screen.Purchasing),
    NavItem("Bookkeeping", Icons.Default.AccountBalance, Screen.Books),
    NavItem("Staff", Icons.Default.Badge, Screen.Staff),
    NavItem("Reports", Icons.Default.BarChart, Screen.Reports),
    NavItem("Settings", Icons.Default.Settings, Screen.Settings),
)

private fun sectionOf(s: Screen): Screen = when (s) {
    is Screen.Job -> Screen.Jobs
    is Screen.Customer -> Screen.Customers
    is Screen.CounterSale -> Screen.CounterSale()
    else -> s
}

@Composable
fun Shell() {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(App.message) {
        val m = App.message ?: return@LaunchedEffect
        snack.showSnackbar(m, withDismissAction = true, duration = SnackbarDuration.Short)
        App.message = null
    }
    Row(Modifier.fillMaxSize()) {
        // Sidebar: solid woven-carbon panel, same surface as the cards
        Column(Modifier.width(232.dp).fillMaxHeight().carbonCard(androidx.compose.ui.graphics.RectangleShape, border = false).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 16.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CarRepair, null, tint = MaterialTheme.colorScheme.onPrimary) }
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(Settings.get(Settings.BUSINESS).take(22), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("Workshop Manager", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val current = sectionOf(App.screen)
                nav.forEach { n ->
                    NavigationDrawerItem(
                        label = { Text(n.label, maxLines = 1, softWrap = false) }, icon = { Icon(n.icon, null) }, selected = current == n.screen,
                        badge = { if (n.key.isNotEmpty()) Text(n.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { App.go(n.screen) }, modifier = Modifier.height(44.dp),
                        colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    )
                }
            }
            TextButton({ App.act { Db.close(); App.loggedIn = false; App.screen = Screen.Dashboard } }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Logout, null); Spacer(Modifier.width(6.dp)); Text("Log out")
            }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (val s = App.screen) {
                Screen.Dashboard -> DashboardScreen()
                Screen.Jobs -> JobsScreen()
                is Screen.Job -> JobScreen(s.id)
                Screen.Bookings -> BookingsScreen()
                Screen.Billing -> BillingScreen()
                is Screen.CounterSale -> CounterSaleScreen(s.customerId)
                Screen.Customers -> CustomersScreen()
                is Screen.Customer -> CustomerScreen(s.id)
                Screen.Inventory -> InventoryScreen()
                Screen.Purchasing -> PurchasingScreen()
                Screen.Books -> BooksScreen()
                Screen.Staff -> StaffScreen()
                Screen.Reports -> ReportsScreen()
                Screen.Settings -> SettingsScreen()
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}
