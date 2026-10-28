package pk.workshop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pk.workshop.core.Dates
import pk.workshop.core.Money
import pk.workshop.data.*
import pk.workshop.print.WhatsApp
import pk.workshop.ui.*
import pk.workshop.ui.Button

@Composable
fun DashboardScreen() {
    val d = remember(App.version) { Reports.dashboard() }
    val reminders = remember(App.version) { Vehicles.dueForService() }
    val low = remember(App.version) { Inventory.list(onlyLow = true).take(8) }
    val open = remember(App.version) { Jobs.list(openOnly = true).take(10) }
    Page("Today, ${Dates.show(Dates.today())}", actions = {
        OutlinedButton({ App.go(Screen.Bookings) }) { IconText(Icons.Default.EventAvailable, "Bookings") }
        OutlinedButton({ App.go(Screen.CounterSale()) }) { IconText(Icons.Default.PointOfSale, "Counter sale") }
        Button({ App.go(Screen.Jobs) }, Modifier.tourAnchor("dash.newjob")) { IconText(Icons.Default.Add, "New job card") }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GettingStarted()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Sales today", Money.rs(d.salesToday), Modifier.weight(1f), accent = true, icon = Icons.Default.TrendingUp, onClick = { App.go(Screen.Billing) })
                Stat("Bills today", d.invoicesToday.toString(), Modifier.weight(1f), icon = Icons.Default.ReceiptLong)
                Stat("Cash in hand", Money.rs(Money.roundToRupee(d.cashInHand)), Modifier.weight(1f), icon = Icons.Default.Payments, onClick = { App.go(Screen.Books) })
                Stat("Customers owe", Money.rs(d.receivables), Modifier.weight(1f), icon = Icons.Default.AccountBalanceWallet, onClick = { App.go(Screen.Reports) })
                Stat("Due now", Money.rs(d.dueToday), Modifier.weight(1f), icon = Icons.Default.Schedule)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Sales this month (before tax)", Money.rs(Money.roundToRupee(d.salesMonth)), Modifier.weight(1f), icon = Icons.Default.CalendarMonth)
                Stat("Profit this month", Money.rs(Money.roundToRupee(d.profitMonth)), Modifier.weight(1f), icon = Icons.Default.Insights)
                Stat("Cars in workshop", d.carsIn.values.sum().toString(), Modifier.weight(1f), icon = Icons.Default.DirectionsCar, onClick = { App.go(Screen.Jobs) })
                Stat("Parts running low", d.lowStock.toString(), Modifier.weight(1f), icon = Icons.Default.Warning, onClick = { App.go(Screen.Inventory) })
                Stat("Cheques in hand", Money.rs(d.chequesInHand), Modifier.weight(1f), icon = Icons.Default.Description)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("Sales, last 14 days", Modifier.weight(2f).height(280.dp)) { BarChart(d.last14) }
                Section("Cars by status", Modifier.weight(1f).height(280.dp)) {
                    if (d.carsIn.isEmpty()) Empty("No cars in the workshop")
                    Defaults.jobStatuses.filter { d.carsIn.containsKey(it) }.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip(s); Spacer(Modifier.weight(1f)); Text(d.carsIn[s].toString(), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("Open job cards", Modifier.weight(1.4f)) {
                    if (open.isEmpty()) Empty("Nothing open")
                    open.forEach { j ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ App.go(Screen.Job(j.id)) }) { Text(j.jobNo) }
                            Text("${j.vehicleLabel} · ${j.customerName}", Modifier.weight(1f), maxLines = 1)
                            Chip(j.status)
                        }
                    }
                }
                Section("Service reminders due (${d.servicesDue})", Modifier.weight(1f)) {
                    if (reminders.isEmpty()) Empty("No reminders due")
                    reminders.take(8).forEach { v ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.label, fontWeight = FontWeight.Medium)
                                Text("${v.ownerName} · ${v.nextServiceDate?.let { Dates.show(it) } ?: ""} ${v.nextServiceKm?.let { "%,d km".format(it) } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ App.act { WhatsApp.send(v.ownerPhone, WhatsApp.reminderText(v), "vehicle", v.id) } }) { Icon(Icons.Default.Chat, "WhatsApp") }
                        }
                    }
                }
                Section("Low stock", Modifier.weight(1f)) {
                    if (low.isEmpty()) Empty("Stock is fine")
                    low.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(p.name, Modifier.weight(1f), maxLines = 1)
                            Text("${pk.workshop.core.Qty.fmt(p.qty)} ${p.unit}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BarChart(points: List<Pair<String, Long>>) {
    val max = (points.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Text("Highest: ${Money.rs(max)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp)) {
            val n = points.size.coerceAtLeast(1)
            val slot = size.width / n
            for (i in 0..3) { val y = size.height * i / 4f; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
            points.forEachIndexed { i, (_, v) ->
                val h = size.height * (v.toFloat() / max.toFloat())
                drawRoundRect(bar, Offset(i * slot + slot * 0.18f, size.height - h), Size(slot * 0.64f, h), CornerRadius(6f, 6f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            points.forEachIndexed { i, (d, _) ->
                Text(if (i % 2 == 0) d.substring(8, 10) + "/" + d.substring(5, 7) else "", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
