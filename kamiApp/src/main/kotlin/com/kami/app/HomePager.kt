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
 * minus-one dashboard, right-swipe once more (from the left edge of
 * minus-one) to the minus-two timetable, left-swipe to the app drawer.
 */
@Composable
internal fun HomePager(
    chat: @Composable () -> Unit,
    minusOne: @Composable () -> Unit,
    minusTwo: @Composable () -> Unit,
    drawer: @Composable (goHome: () -> Unit) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 2, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> minusTwo()

            1 -> minusOne()

            2 -> chat()

            else -> drawer {
                scope.launch { pagerState.animateScrollToPage(2) }
            }
        }
    }
    // Registered after the pager, so it wins over the chat's double-back exit.
    BackHandler(enabled = pagerState.currentPage != 2) {
        scope.launch { pagerState.animateScrollToPage(2) }
    }
}
