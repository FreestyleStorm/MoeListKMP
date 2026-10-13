package com.axiel7.moelist

import com.axiel7.moelist.data.utils.NOTIFICATIONS_DATA_STORE
import com.axiel7.moelist.screens.details.topbar.MediaNotificationsViewModel
import com.axiel7.moelist.screens.more.notifications.NotificationsViewModel
import dev.brewkits.kmpworkmanager.KmpWorkManager
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val androidViewModelModule = module {
    viewModel { parameters ->
        com.axiel7.moelist.screens.more.settings.updates.AppUpdateViewModel(parameters.get())
    }
    viewModel {
        MediaNotificationsViewModel(
            notificationWorkerManager = get(),
            scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler
        )
    }
    viewModel {
        NotificationsViewModel(
            dataStore = get(named(NOTIFICATIONS_DATA_STORE)),
            notificationWorkerManager = get(),
            scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler,
        )
    }
}
