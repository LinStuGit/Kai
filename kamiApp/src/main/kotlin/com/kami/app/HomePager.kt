package com.kami.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Home pager with the chat page in the middle: right-swipe to the
 * minus-one dashboard, left-swipe to the app drawer.
 */
@Composable
internal fun HomePager(
    chat: @Composable () -> Unit,
    minusOne: @Composable () -> Unit,
    drawer: @Composable (goHome: () -> Unit) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> minusOne()

            1 -> chat()

            else -> drawer {
                scope.launch { pagerState.animateScrollToPage(1) }
            }
        }
    }
    // Registered after the pager, so it wins over the chat's double-back exit.
    BackHandler(enabled = pagerState.currentPage != 1) {
        scope.launch { pagerState.animateScrollToPage(1) }
    }
}
