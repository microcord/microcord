package com.discord.app

import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * `by appViewModels { WidgetThreadStatusViewModel() }` — a fragment-scoped view model built by a
 * caller-supplied producer instead of by reflection, so a view model can take constructor
 * arguments (its store observable, the arguments bundle) without a hand-written factory class.
 */
inline fun <reified VM : ViewModel> Fragment.appViewModels(
    noinline viewModelProducer: () -> VM,
): Lazy<VM> = viewModels { producerViewModelFactory(viewModelProducer) }

/** [appViewModels], scoped to the host activity so sibling fragments share one instance. */
inline fun <reified VM : ViewModel> Fragment.appActivityViewModels(
    noinline viewModelProducer: () -> VM,
): Lazy<VM> = activityViewModels { producerViewModelFactory(viewModelProducer) }

/** Factory that ignores the requested class and returns whatever [viewModelProducer] builds. */
fun producerViewModelFactory(viewModelProducer: () -> ViewModel): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModelProducer() as T
    }
