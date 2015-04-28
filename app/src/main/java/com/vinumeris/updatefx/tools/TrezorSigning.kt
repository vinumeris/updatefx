package com.vinumeris.updatefx.tools

import com.google.common.base.Optional
import com.google.common.eventbus.Subscribe
import com.google.common.io.BaseEncoding
import com.google.common.util.concurrent.SettableFuture
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.wallet.KeyChain
import org.multibit.hd.hardware.core.HardwareWalletService
import org.multibit.hd.hardware.core.events.HardwareWalletEvent
import org.multibit.hd.hardware.core.events.HardwareWalletEventType.MESSAGE_SIGNATURE
import org.multibit.hd.hardware.core.events.HardwareWalletEventType.SHOW_DEVICE_DETACHED
import org.multibit.hd.hardware.core.events.HardwareWalletEventType.SHOW_DEVICE_READY
import org.multibit.hd.hardware.core.events.HardwareWalletEventType.SHOW_PIN_ENTRY
import org.multibit.hd.hardware.core.events.HardwareWalletEvents
import org.multibit.hd.hardware.core.messages.MessageSignature
import org.multibit.hd.hardware.core.messages.PinMatrixRequest
import org.multibit.hd.hardware.core.messages.PinMatrixRequestType
import org.multibit.hd.hardware.core.wallets.HardwareWallets
import org.multibit.hd.hardware.trezor.clients.TrezorHardwareWalletClient
import org.multibit.hd.hardware.trezor.wallets.v1.TrezorV1HidHardwareWallet
import java.util.Base64
import java.util.Scanner
import java.util.concurrent.ExecutionException


/**
 * Support for signing update indexes using the TREZOR hardware wallet. The advantage of using this device is that there
 * is a screen and a button, so you know when you're signing something and thus it's harder for a compromised host to
 * sneak unexpected signing operations past you. An attack must instead inject bad code into the build without the
 * developer noticing, rather than attacking the signing infrastructure, and that can be addressed with a variety of
 * other techniques.
 */

open class HWSigningOpException : Exception()
class DeviceDisconnectedException : HWSigningOpException()
class NoKeysOnDeviceException : HWSigningOpException()
class UnexpectedPINRequestException: HWSigningOpException()
class MismatchedKeyException : HWSigningOpException()

private val PIN_MESSAGE = """Please enter your PIN by providing the positions on the keypad below, instead of the PIN itself:

7  8  9
4  5  6
1  2  3

PIN: """

// Returns a base64 encoded signature.
fun signWithTrezor(hash: Sha256Hash, expectedKey: ECKey? = null): String {
    // Use factory to statically bind the specific hardware wallet
    val wallet = HardwareWallets.newUsbInstance(javaClass<TrezorV1HidHardwareWallet>(),
            Optional.absent(), Optional.absent(), Optional.absent())

    val client = TrezorHardwareWalletClient(wallet)
    val service = HardwareWalletService(client)

    val future: SettableFuture<String> = SettableFuture.create()

    val eventHandler = object {
        private val msg = BaseEncoding.base16().encode(hash.getBytes()).toLowerCase()
        private var started = false

        Subscribe fun onHardwareWalletEvent(event: HardwareWalletEvent) {
            when (event.getEventType()) {
                SHOW_DEVICE_DETACHED -> {
                    if (started && !future.isDone())
                        future.setException(DeviceDisconnectedException())
                    else if (!started)
                        println("Waiting for TREZOR to be plugged in ...")
                }

                SHOW_DEVICE_READY -> {
                    if (!service.isWalletPresent()) {
                        println("You need to have created a wallet on your TREZOR first")
                        future.setException(NoKeysOnDeviceException())
                        return
                    }

                    // This kicks off a UI flow that may involve us passing through PIN authentication before
                    // we receive the final signature.
                    started = true
                    service.signMessage(0, KeyChain.KeyPurpose.AUTHENTICATION, 0, msg.toByteArray())
                }

                SHOW_PIN_ENTRY -> {
                    print(PIN_MESSAGE)
                    val matrixReq = event.getMessage().get() as PinMatrixRequest
                    val keyboard = Scanner(System.`in`)
                    if (matrixReq.getPinMatrixRequestType() != PinMatrixRequestType.CURRENT) {
                        future.setException(UnexpectedPINRequestException())
                        return
                    }
                    val pin = keyboard.next()
                    service.providePIN(pin)
                }

                MESSAGE_SIGNATURE -> {
                    val sig = event.getMessage().get() as MessageSignature
                    val b64sig = Base64.getEncoder().encodeToString(sig.getSignature())
                    val key = ECKey.signedMessageToKey(msg, b64sig)
                    if (expectedKey != null && key != expectedKey)
                        future.setException(MismatchedKeyException())
                    else
                        future.set(b64sig)
                }
            }
        }
    }

    HardwareWalletEvents.subscribe(eventHandler)
    service.start()
    try {
        return future.get()
    } catch (e: ExecutionException) {
        throw e.getCause()!!
    } finally {
        service.stopAndWait()
    }
}