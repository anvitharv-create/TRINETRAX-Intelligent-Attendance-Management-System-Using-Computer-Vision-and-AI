package com.trinetrax.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trinetrax.attendance.TrinetraXApp
import com.trinetrax.attendance.core.SessionManager
import com.trinetrax.attendance.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-scoped state: session identity + logout. Screen-specific data is loaded
 * inside each screen from the repository so responses always reflect the
 * backend at that moment.
 */
class SessionViewModel(private val session: SessionManager) : ViewModel() {

    val repo = AppRepository(session)

    val token = session.token
    val user = session.user

    /** Scope for one-off screen requests; cancelled automatically when the app clears. */
    fun requestFlowScope(): CoroutineScope = viewModelScope

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.logout()
            onDone()
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SessionViewModel(TrinetraXApp.instance.session) as T
            }
        }
    }
}
