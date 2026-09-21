package com.kami.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** Home pager: page 0 = chat home, page 1 = minus-one dashboard (swipe left). */
@Composable
internal fun HomePager(
    chat: @Composable () -> Unit,
    minusOne: @Composable () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        if (page == 0) chat() else minusOne()
    }
    // Registered after the pager, so it wins over the chat's double-back exit.
    BackHandler(enabled = pagerState.currentPage == 1) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }
}
