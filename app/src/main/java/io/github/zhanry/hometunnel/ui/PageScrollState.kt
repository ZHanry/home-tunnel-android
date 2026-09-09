package io.github.zhanry.hometunnel.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/** Keep each destination's position, but start at the top for a different owner, query or page. */
@Composable
internal fun pageScrollState(destination: Any, contentKey: Any = Unit): LazyListState {
    val states = remember { mutableMapOf<Any, LazyListState>() }
    val contents = remember { mutableMapOf<Any, Any>() }
    val state = states.getOrPut(destination) { LazyListState() }
    LaunchedEffect(destination, contentKey) {
        if (contents[destination] != contentKey) {
            state.scrollToItem(0)
            contents[destination] = contentKey
        }
    }
    return state
}
