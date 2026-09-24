package com.guftugu.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.guftugu.app.GuftuguApp
import com.guftugu.app.di.AppGraph

/**
 * The one ViewModel factory pattern used by every screen: the VM is built from [AppGraph]
 * singletons, scoped to the current NavBackStackEntry, and keyed so two instances of the
 * same screen (e.g. two chats on the back stack) never share state.
 */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = GuftuguApp.graph(LocalContext.current)
    val factory = remember(graph) { viewModelFactory { initializer { create(graph) } } }
    return viewModel(key = key, factory = factory)
}
