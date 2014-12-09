package com.vinumeris.updatefx.tools

import com.google.common.io.BaseEncoding
import com.google.protobuf.ByteString
import com.vinumeris.updatefx.DeltaCalculator
import com.vinumeris.updatefx.UFXProtocol
import com.vinumeris.updatefx.Utils
import joptsimple.OptionParser
import joptsimple.OptionSet
import joptsimple.OptionSpec
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Wallet
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.store.UnreadableWalletException
import org.bitcoinj.utils.BriefLogFormatter

import java.io.*
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.HashMap
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.logging.Level
import java.util.logging.LogManager
import kotlin.platform.platformStatic
import org.spongycastle.crypto.params.KeyParameter
import org.bitcoinj.crypto.KeyCrypter
import org.bitcoinj.crypto.KeyCrypterScrypt

/**
 * This app takes a working directory that contains a subdir called "builds", containing each version of the app
 * named like 1.jar, 2.jar, 3.jar etc. It creates in a subdir called "site" a set of patch files and an index.
 * In the working directory it creates a bitcoinj format wallet that holds signing keys. If the jar file contains
 * a file in the root package named "update-description.txt" then the first line is used as the one liner, and the rest
 * is used as the update description.
 */
public class UFXPrepare {
    class object {
        private fun printIndex(file: File) {
            val proto = UFXProtocol.SignedUpdates.parseFrom(file.readBytes())
            val updates = UFXProtocol.Updates.parseFrom(proto.getUpdates())

            println("UpdateFX index (v${updates.getVersion()}): ${updates.getUpdatesCount()} updates defined:")
            println()

            for (update in updates.getUpdatesList()) {
                val version = update.getVersion()
                val patchSize = update.getPatchSize()
                val gzipped = if (update.getGzipped()) " gzipped" else ""
                print("Update $version ($patchSize bytes${gzipped})")
                if (update.getDescriptionCount() > 0) {
                    val desc = update.getDescription(0)
                    println(": ${desc.getOneLiner()}")
                    println(desc.getDescription())
                }
                println()
                val preHash = BaseEncoding.base16().encode(update.getPreHash().toByteArray()).toLowerCase()
                val patchHash = BaseEncoding.base16().encode(update.getPatchHash().toByteArray()).toLowerCase()
                val postHash = BaseEncoding.base16().encode(update.getPostHash().toByteArray()).toLowerCase()
                println("PreHash:     $preHash")
                println("Patch hash:  $patchHash")
                println("PostHash:    $postHash")
                for (url in update.getUrlsList()) {
                    println("  $url")
                }
                if (update != updates.getUpdatesList().last)
                    println("----------")
            }
        }

        platformStatic
        public fun main(args: Array<String>) {
            val parser = OptionParser()
            // Base URL where the patches will be served. Can be specified multiple times.
            val url = parser.accepts("url").withRequiredArg()
            parser.accepts("debuglog")
            val printIndex = parser.accepts("print-index").withRequiredArg()
            // If set, which version to start decompressing jars from and applying gzip to the resulting patch files.
            val gzipFromStr = parser.accepts("gzip-from").withRequiredArg().defaultsTo("-1")
            val options = parser.parse(*args)

            if (options.has("debuglog")) {
                BriefLogFormatter.init()
            } else {
                // Disable logspam unless there is a flag.
                LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE)
            }

            if (options.has(printIndex)) {
                val indexPath = Paths.get(options.valueOf(printIndex)!!)
                if (!Files.exists(indexPath)) {
                    println("Couldn't find index $indexPath")
                    return
                }
                printIndex(indexPath.toFile())
                return
            }

            if (options.nonOptionArguments().isEmpty()) {
                println("You must specify a working directory.")
                return
            }

            val gzipFrom = gzipFromStr.value(options).toInt()
            val working = Paths.get(options.nonOptionArguments().get(0) as String)

            if (options.valuesOf<String>(url).isEmpty()) {
                println("You must specify at least one --url")
                return
            }

            val builds = working.resolve("builds")
            if (!Files.isDirectory(builds)) {
                println("Working directory must have a builds subdirectory.")
                return
            }
            val site = working.resolve("site")
            if (Files.exists(site)) {
                // Delete existing patch files.
                for (path in Utils.listDir(site)) {
                    if (path.toString().endsWith(".bpatch"))
                        Files.delete(path)
                }
            } else {
                Files.createDirectory(site)
            }
            val params = MainNetParams.get()
            val walletFile = working.resolve("wallet")
            val wallet: Wallet
            if (Files.exists(walletFile)) {
                wallet = Wallet.loadFromFile(walletFile.toFile())
            } else {
                wallet = Wallet(params)
                println("Creating a new key store (wallet), so you must select a signing key password.")
                val password1 = askPassword()
                println("Please enter the password again.")
                val password2 = askPassword()
                if (password1 != password2) {
                    println("Your passwords did not match, quitting")
                    return
                }
                val crypter = KeyCrypterScrypt(1048576)    // ~2 seconds to decrypt
                wallet.encrypt(crypter, crypter.deriveKey(password1))
                wallet.saveToFile(walletFile.toFile())
            }
            // Process the jars to remove timestamps and decompress. This does nothing if the zip is already processed.
            // Version ranges can be excluded for compatibility with old Lighthouse versions.
            // TODO: Once all testers are upgraded, remove the backwards compat stuff.
            // Also extract descriptions, if they exist.
            val descriptions = HashMap<Int, UFXProtocol.UpdateDescription>()
            val strippedZipsDir = builds.resolve("processed")
            if (!Files.isDirectory(strippedZipsDir))
                Files.createDirectory(strippedZipsDir)
            for (path in Utils.listDir(builds)) {
                if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                    val v = path.getFileName().toString().replace(".jar", "").toInt()
                    val processed = strippedZipsDir.resolve(path.getFileName())
                    if (v >= gzipFrom)
                        ProcessZIP.process(path, processed)
                    else
                        Files.copy(path, processed)
                    val jar = JarFile(processed.toFile())
                    val entry = jar.getJarEntry("update-description.txt") ?: continue
                    jar.getInputStream(entry).use { stream ->
                        stream.reader(Charsets.UTF_8).useLines { lines ->
                            val l = lines.toList()
                            if (l.size > 0) {
                                val desc = UFXProtocol.UpdateDescription.newBuilder()
                                desc.setOneLiner(l.first())
                                if (l.size > 1)
                                    desc.setDescription(l.drop(1).join("\n"))
                                descriptions.put(v, desc.build())
                            }
                        }
                    }
                }
            }

            // Generate the patch files.
            val patches = DeltaCalculator.process(strippedZipsDir.toAbsolutePath(), site.toAbsolutePath(), gzipFrom)
            // Build an index.
            val updates = UFXProtocol.Updates.newBuilder()
            for (patch in patches) {
                val update = UFXProtocol.Update.newBuilder()
                val num = Integer.parseInt(patch.path.getFileName().toString().replaceAll("\\.jar\\.bpatch", ""))
                update.setVersion(num)
                update.setPatchSize(patch.patchSize)
                update.setPreHash(ByteString.copyFrom(patch.preHash))
                update.setPatchHash(ByteString.copyFrom(patch.patchHash))
                update.setPostHash(ByteString.copyFrom(patch.postHash))
                update.setGzipped(num >= gzipFrom)
                for (baseURL in url.values(options)) {
                    try {
                        val uri = URI((if (baseURL.endsWith("/")) baseURL else baseURL.concat("/")) + num + ".jar.bpatch")
                        update.addUrls(uri.toString())
                    } catch (e: URISyntaxException) {
                        println("Base URL is malformed: $baseURL")
                        return
                    }
                }
                val desc = descriptions.get(num)
                if (desc != null)
                    update.addDescription(desc)
                updates.addUpdates(update)
            }
            // Sign it.
            updates.setVersion(1)
            val signedUpdates = UFXProtocol.SignedUpdates.newBuilder()
            val bits = updates.build().toByteArray()
            val hash = Utils.sha256(bits)
            var key = wallet.currentReceiveKey()

            if (key.isEncrypted()) {
                while (true) {
                    val password = askPassword()
                    try {
                        key = key.decrypt(wallet.getKeyCrypter().deriveKey(password))
                        break
                    } catch (e: Exception) {
                        println("Password is incorrect, please try again")
                    }
                }
            }

            val signature = key.signMessage(BaseEncoding.base16().encode(hash).toLowerCase())
            signedUpdates.addSignatures(signature)
            signedUpdates.setUpdates(ByteString.copyFrom(bits))
            // Save the index to the sites dir
            Files.write(site.resolve("index"), signedUpdates.build().toByteArray())
            println("Signed with public key " + BaseEncoding.base16().encode(key.getPubKey()))
        }

        private fun askPassword(): String {
            val c = System.console()
            if (c == null) {
                println("No console found to request password with, quitting")
                System.exit(1)
            }
            return String(c.readPassword("Enter signing key password: "))
        }
    }
}
