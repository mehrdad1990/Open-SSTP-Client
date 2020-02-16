package kittoku.opensstpclient.negotiator

import kittoku.opensstpclient.layer.IpcpState
import kittoku.opensstpclient.layer.PppClient
import kittoku.opensstpclient.misc.*
import kittoku.opensstpclient.unit.*


internal fun PppClient.tryReadingIpcp(frame: IpcpFrame): Boolean {
    try {
        frame.read(incomingBuffer)
    } catch (e: DataUnitParsingError) {
        parent.informDataUnitParsingError(frame, e)
        kill()
        return false
    }

    if (frame is IpcpConfigureAck || frame is IpcpConfigureNak) {
        if (frame.id != currentIpcpRequestId) return false
    }

    return true
}

internal suspend fun PppClient.sendIpcpConfigureRequest() {
    if (ipcpCounter.isExhausted) {
        parent.informCounterExhausted(::sendIpcpConfigureRequest)
        kill()
        return
    }

    ipcpCounter.consume()

    globalIdentifier++
    currentIpcpRequestId = globalIdentifier
    val sending = IpcpConfigureRequest()
    sending.id = currentIpcpRequestId
    if (!networkSetting.mgIp.isRejected) sending.optionIp =
        parent.networkSetting.mgIp.create()
    if (!networkSetting.mgDns.isRejected) sending.optionDns =
        parent.networkSetting.mgDns.create()
    sending.update()
    addControlUnit(sending)

    ipcpTimer.reset()
}

internal suspend fun PppClient.sendIpcpConfigureAck(received: IpcpConfigureRequest) {
    val sending = IpcpConfigureAck()
    sending.id = received.id
    sending.options = received.options
    sending.update()
    addControlUnit(sending)
}

internal suspend fun PppClient.sendIpcpConfigureNak(received: IpcpConfigureRequest) {
    if (received.optionIp != null) {
        received.optionIp = networkSetting.mgIp.create()
    }

    if (received.optionDns != null) {
        received.optionDns = networkSetting.mgDns.create()
    }

    val sending = IpcpConfigureNak()
    sending.id = received.id
    sending.options = received.options
    sending.update()
    addControlUnit(sending)
}

internal suspend fun PppClient.sendIpcpConfigureReject(received: IpcpConfigureRequest) {
    val sending = IpcpConfigureReject()
    sending.id = received.id
    sending.options = received.extractUnknownOption()
    sending.update()
    addControlUnit(sending)
}

internal suspend fun PppClient.receiveIpcpConfigureRequest() {
    val received = IpcpConfigureRequest()
    if (!tryReadingIpcp(received)) return

    if (ipcpState == IpcpState.OPENED) {
        parent.informInvalidUnit(::receiveIpcpConfigureRequest)
        kill()
        return
    }

    if (received.hasUnknownOption) {
        sendIpcpConfigureReject(received)

        if (ipcpState == IpcpState.ACK_SENT) ipcpState = IpcpState.REQ_SENT

        return
    }

    val isIpOk = received.optionIp?.let { networkSetting.mgIp.compromiseReq(it) } ?: true
    val isDnsOk = received.optionDns?.let { networkSetting.mgDns.compromiseReq(it) } ?: true

    if (isIpOk && isDnsOk) {
        sendIpcpConfigureAck(received)

        when (ipcpState) {
            IpcpState.REQ_SENT -> ipcpState = IpcpState.ACK_SENT
            IpcpState.ACK_RCVD -> ipcpState = IpcpState.OPENED
        }
    } else {
        if (isIpOk) received.optionIp = null
        if (isDnsOk) received.optionDns = null
        sendIpcpConfigureNak(received)

        if (ipcpState == IpcpState.ACK_SENT) ipcpState = IpcpState.REQ_SENT
    }
}


internal suspend fun PppClient.receiveIpcpConfigureAck() {
    val received = IpcpConfigureAck()
    if (!tryReadingIpcp(received)) return

    when (ipcpState) {
        IpcpState.REQ_SENT -> {
            ipcpCounter.reset()
            ipcpState = IpcpState.ACK_RCVD
        }
        IpcpState.ACK_RCVD -> {
            sendIpcpConfigureRequest()
            ipcpState = IpcpState.REQ_SENT
        }
        IpcpState.ACK_SENT -> {
            ipcpCounter.reset()
            ipcpState = IpcpState.OPENED
        }
        IpcpState.OPENED -> {
            parent.informInvalidUnit(::receiveIpcpConfigureAck)
            kill()
            return
        }

    }
}

internal suspend fun PppClient.receiveIpcpConfigureNak() {
    val received = IpcpConfigureNak()
    if (!tryReadingIpcp(received)) return

    if (ipcpState == IpcpState.OPENED) {
        parent.informInvalidUnit(::receiveIpcpConfigureNak)
        kill()
        return
    }

    received.optionIp?.also { networkSetting.mgIp.compromiseNak(it) }
    received.optionDns?.also { networkSetting.mgDns.compromiseNak(it) }
    sendIpcpConfigureRequest()

    if (ipcpState == IpcpState.ACK_RCVD) ipcpState = IpcpState.REQ_SENT
}

internal suspend fun PppClient.receiveIpcpConfigureReject() {
    val received = IpcpConfigureReject()
    if (!tryReadingIpcp(received)) return

    if (received.optionIp != null) {
        parent.informOptionRejected(networkSetting.mgIp.create())
        kill()
        return
    }

    if (received.optionDns != null) {
        networkSetting.mgDns.isRejected = true
    }

    when (ipcpState) {
        IpcpState.REQ_SENT, IpcpState.ACK_SENT -> {
            ipcpCounter.reset()
            sendIpcpConfigureRequest()
        }
        IpcpState.ACK_RCVD -> {
            sendIpcpConfigureRequest()
            ipcpState = IpcpState.REQ_SENT
        }
        IpcpState.OPENED -> {
            parent.informInvalidUnit(::receiveIpcpConfigureReject)
            kill()
            return
        }
    }
}