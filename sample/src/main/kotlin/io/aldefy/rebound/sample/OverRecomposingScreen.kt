package io.aldefy.rebound.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OverRecomposingScreen() {
    var counter by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(16) // ~60fps updates
            counter++
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Rebound Sample", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Counter: $counter")
        Spacer(modifier = Modifier.height(16.dp))
        StableList()
    }
}

@Composable
fun StableList() {
    val items = remember { List(20) { "Item #$it" } }
    LazyColumn {
        items(items) { item ->
            ListItem(headlineContent = { Text(item) })
        }
    }
}
