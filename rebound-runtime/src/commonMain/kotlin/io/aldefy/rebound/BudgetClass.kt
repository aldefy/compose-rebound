package io.aldefy.rebound

enum class BudgetClass(val baseBudgetPerSecond: Int) {
    SCREEN(3),        // Full screen composables — should rarely recompose
    CONTAINER(10),    // Layout containers with children (Column, Box, etc.)
    INTERACTIVE(30),  // Responds to user input (buttons, text fields)
    LIST_ITEM(60),    // Items in LazyColumn/LazyRow — recycled frequently
    ANIMATED(120),    // Animation-driven composables — high rate expected
    LEAF(5),          // Terminal composables with no children — very cheap but shouldn't over-trigger
    UNKNOWN(30)       // Unclassified — permissive default to reduce noise
}
