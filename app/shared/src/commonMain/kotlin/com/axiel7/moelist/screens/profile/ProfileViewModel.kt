package com.axiel7.moelist.screens.profile

import androidx.lifecycle.viewModelScope
import com.axiel7.moelist.data.repository.DefaultPreferencesRepository
import com.axiel7.moelist.data.repository.UserRepository
import com.axiel7.moelist.ui.base.model.ListStatus
import com.axiel7.moelist.ui.base.model.Stat
import com.axiel7.moelist.ui.base.viewmodel.BaseViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class ProfileViewModel(
    private val userRepository: UserRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository
) : BaseViewModel<ProfileUiState>(), ProfileEvent {

    override val mutableUiState = MutableStateFlow(ProfileUiState())
    private var refreshJob: Job? = null

    override fun refreshProfile() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            setLoading(mutableUiState.value.user == null)
            val user = userRepository.getMyUser()

            if (user == null || user.message != null) {
                setLoading(false)
                showMessage(user?.message)
            } else {
                val tempAnimeStatList = mutableListOf<Stat<ListStatus>>()
                user.animeStatistics?.let { stats ->
                    tempAnimeStatList.add(
                        Stat(
                            type = ListStatus.WATCHING,
                            value = stats.numItemsWatching?.toFloat() ?: 0f
                        )
                    )
                    tempAnimeStatList.add(
                        Stat(
                            type = ListStatus.COMPLETED,
                            value = stats.numItemsCompleted?.toFloat() ?: 0f
                        )
                    )
                    tempAnimeStatList.add(
                        Stat(
                            type = ListStatus.ON_HOLD,
                            value = stats.numItemsOnHold?.toFloat() ?: 0f
                        )
                    )
                    tempAnimeStatList.add(
                        Stat(
                            type = ListStatus.DROPPED,
                            value = stats.numItemsDropped?.toFloat() ?: 0f
                        )
                    )
                    tempAnimeStatList.add(
                        Stat(
                            type = ListStatus.PLAN_TO_WATCH,
                            value = stats.numItemsPlanToWatch?.toFloat() ?: 0f
                        )
                    )

                    if (user.picture != null && user.picture != mutableUiState.value.profilePictureUrl) {
                        defaultPreferencesRepository.setProfilePicture(user.picture)
                    }
                }

                mutableUiState.update {
                    it.copy(
                        user = user,
                        profilePictureUrl = user.picture,
                        animeStats = tempAnimeStatList.toImmutableList(),
                        isLoading = false,
                        isLoadingManga = user.name != null,
                    )
                }
            }
        }
    }
}
