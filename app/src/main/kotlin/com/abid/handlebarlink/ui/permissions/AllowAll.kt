package com.abid.handlebarlink.ui.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Allow all, walked as CarLink's setup does it (carlink-src SetupScreen/SetupModel `nextToAllow`): every
 * missing runtime permission in one request (Android shows its dialogs in turn), then each special access in
 * its own Android screen, one at a time, each awaited before the next. Every item is asked once per walk, so
 * a refusal ends it rather than asking again; statuses are read fresh from Android before each step. Returns
 * what starts a walk.
 */
@Composable
fun rememberAllowAll(states: List<AccessState>): () -> Unit {
    var asked by remember { mutableStateOf<Set<Int>?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    val runtime =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
            // Asked now: a later refusal without a rationale is for good, on every screen.
            AskedPermissions.add(answers.keys.toList())
            waiting = false
            step++
        }
    val screen =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            waiting = false
            step++
        }
    LaunchedEffect(step) {
        val done = asked ?: return@LaunchedEffect
        if (waiting) return@LaunchedEffect
        val next = nextToAllow(states.map { it.refresh() to it.request }, done)
        if (next.isEmpty()) {
            asked = null
            return@LaunchedEffect
        }
        asked = done + next
        waiting = true
        when (val first = states[next.first()].request) {
            is AccessRequest.Runtime -> {
                runtime.launch(
                    next.flatMap { (states[it].request as AccessRequest.Runtime).permissions }.toTypedArray(),
                )
            }

            is AccessRequest.Screen -> {
                screen.launch(first.intent())
            }

            AccessRequest.None -> {
                waiting = false
            }
        }
    }
    return {
        asked = emptySet()
        waiting = false
        step++
    }
}

/**
 * What Allow all asks next, by index: every missing runtime item together, else the next missing special
 * access; never one already [asked] in this walk. Empty when the walk is over.
 */
internal fun nextToAllow(
    items: List<Pair<Access, AccessRequest>>,
    asked: Set<Int>,
): List<Int> {
    val missing =
        items.indices.filter {
            it !in asked && items[it].first != Access.GRANTED &&
                items[it].second != AccessRequest.None
        }
    return missing.filter { items[it].second is AccessRequest.Runtime }.ifEmpty { missing.take(1) }
}
