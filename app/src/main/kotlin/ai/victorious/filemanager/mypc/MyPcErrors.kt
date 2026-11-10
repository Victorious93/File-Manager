package ai.victorious.filemanager.mypc

import ai.victorious.filemanager.core.mypc.HostKeyMismatchException
import ai.victorious.filemanager.core.mypc.HostKeyUnpinnedException
import ai.victorious.filemanager.core.mypc.KeyRejectedException
import ai.victorious.filemanager.core.mypc.PcUnreachableException
import ai.victorious.filemanager.core.net.CredentialRedactor

object MyPcErrors {
    fun explain(t: Throwable): String = when (t) {
        is HostKeyMismatchException -> "Blocked: the PC's host key changed. Nothing was sent. Re-pair the PC (see Tools → My PC)."
        is HostKeyUnpinnedException -> "Open Tools → My PC and confirm the PC's host key first."
        is KeyRejectedException -> "The PC refused this app's key. In Tools → My PC tap Key and add it to ~/.ssh/authorized_keys."
        is PcUnreachableException -> "Can't reach the PC. Same Wi-Fi? Away from home, turn on the Tailscale app."
        else -> CredentialRedactor.safeMessage(t)
    }
}
