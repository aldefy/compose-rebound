package io.aldefy.rebound.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.aldefy.rebound.BudgetClass
import io.aldefy.rebound.ReboundBudget
import kotlinx.coroutines.delay

@Composable
fun OverRecomposingScreen() {
    ReboundSampleApp()
}

@Composable
fun ReboundSampleApp() {
    var mode by remember { mutableStateOf(DemoMode.Violation) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Rebound Sample", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Run doctor, check, then summary. The default tab intentionally breaks a SCREEN budget.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { mode = DemoMode.Violation },
                enabled = mode != DemoMode.Violation,
                modifier = Modifier
                    .height(48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Show violation demo"
                    }
            ) {
                Text("Violation")
            }
            OutlinedButton(
                onClick = { mode = DemoMode.Fixed },
                enabled = mode != DemoMode.Fixed,
                modifier = Modifier
                    .height(48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Show fixed demo"
                    }
            ) {
                Text("Fixed")
            }
        }

        when (mode) {
            DemoMode.Violation -> BudgetViolationScreen(modifier = Modifier.weight(1f))
            DemoMode.Fixed -> BudgetFixedScreen(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun BudgetViolationScreen(modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            frame++
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DemoCard(
            title = "Bad: state read at screen scope",
            body = "BudgetViolationScreen reads a 60fps ticker directly. Rebound should report this screen over its 3/s SCREEN budget."
        )
        Text("Screen-level frame: $frame", style = MaterialTheme.typography.titleMedium)
        StableList(modifier = Modifier.weight(1f))
    }
}

@Composable
fun BudgetFixedScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DemoCard(
            title = "Fixed: isolate the fast state",
            body = "The screen stays stable. The 60fps ticker is scoped to an ANIMATED child with the correct budget."
        )
        AnimatedTickerBadge()
        StableList(modifier = Modifier.weight(1f))
    }
}

@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun AnimatedTickerBadge() {
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            frame++
        }
    }

    val progress = (frame % 120) / 119f
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                contentDescription = "Animated ticker running in child scope"
            }
    ) {
        val centerY = size.height / 2f
        val radius = 8.dp.toPx()
        val x = (size.width * progress).coerceIn(radius, size.width - radius)

        drawLine(
            color = Color(0xFFB8C4CC),
            start = Offset(radius, centerY),
            end = Offset(size.width - radius, centerY),
            strokeWidth = 4.dp.toPx()
        )
        drawCircle(
            color = Color(0xFF176B5B),
            radius = radius,
            center = Offset(x, centerY)
        )
    }
}

@Composable
fun DemoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun StableList(modifier: Modifier = Modifier) {
    val items = remember { List(20) { "Item #$it" } }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items) { item ->
            ListItem(headlineContent = { Text(item) })
        }
    }
}

enum class DemoMode {
    Violation,
    Fixed
}
