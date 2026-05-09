package com.comfymobile.presentation.connection

import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.network.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeConnectionStateMachine(
    initialState: ConnectionState = ConnectionState.Connected,
) : ConnectionStateMachineFacade {
    private val mutableState = MutableStateFlow(initialState)
    private val mutableErrors = MutableSharedFlow<ConnectError>(extraBufferCapacity = 16)
    val dispatchedInputs = mutableListOf<ConnectionInput>()

    override val currentState: StateFlow<ConnectionState> = mutableState
    override val errors: Flow<ConnectError> = mutableErrors

    override fun dispatch(input: ConnectionInput) {
        dispatchedInputs += input
        if (input is ConnectionInput.Retry) {
            mutableState.value = ConnectionState.Reconnecting(
                com.comfymobile.data.network.ReconnectReason.LAN_FLAKE,
            )
        }
    }

    fun setState(state: ConnectionState) {
        mutableState.value = state
    }

    fun emitError(error: ConnectError) {
        mutableErrors.tryEmit(error)
    }
}
