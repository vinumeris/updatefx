package com.vinumeris.updatefx.tools;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.store.UnreadableWalletException;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;
import com.vinumeris.updatefx.DeltaCalculator;
import com.vinumeris.updatefx.UFXProtocol;
import com.vinumeris.updatefx.Utils;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * This app takes a working directory that contains a subdir called "builds", containing each version of the app
 * named like 1.jar, 2.jar, 3.jar etc. It creates in a subdir called "site" a set of patch files and an index.
 * In the working directory it creates a bitcoinj format wallet that holds signing keys.
 */
public class UFXPrepare {
    public static void main(String[] args) throws IOException, UnreadableWalletException {
        OptionParser parser = new OptionParser();
        // Base URL where the patches will be served. Can be specified multiple times.
        OptionSpec<String> url = parser.accepts("url").withRequiredArg();
        parser.accepts("debuglog");
        OptionSet options = parser.parse(args);

        if (options.has("debuglog")) {
            BriefLogFormatter.init();
        } else {
            // Disable logspam unless there is a flag.
            java.util.logging.Logger logger = LogManager.getLogManager().getLogger("");
            logger.setLevel(Level.SEVERE);
        }

        if (options.nonOptionArguments().isEmpty()) {
            System.err.println("You must specify a working directory.");
            return;
        }
        if (options.valuesOf(url).isEmpty()) {
            System.err.println("You must specify at least one --url");
            return;
        }
        Path working = Paths.get((String) options.nonOptionArguments().get(0));
        Path builds = working.resolve("builds");
        if (!Files.isDirectory(builds)) {
            System.err.println("Working directory must have a builds subdirectory.");
            return;
        }
        Path site = working.resolve("site");
        if (Files.exists(site)) {
            // Delete existing patch files.
            for (Path path : Utils.listDir(site)) {
                if (path.toString().endsWith(".bpatch"))
                    Files.delete(path);
            }
        } else {
            Files.createDirectory(site);
        }
        NetworkParameters params = MainNetParams.get();
        Path walletFile = working.resolve("wallet");
        Wallet wallet;
        if (Files.exists(walletFile)) {
            wallet = Wallet.loadFromFile(walletFile.toFile());
        } else {
            wallet = new Wallet(params);
            wallet.saveToFile(walletFile.toFile());
        }
        // Generate the patch files.
        DeltaCalculator.main(new String[] { builds.toAbsolutePath().toString(), site.toAbsolutePath().toString() });
        // Build an index.
        UFXProtocol.Updates.Builder updates = UFXProtocol.Updates.newBuilder();
        for (Path path : Utils.listDir(site)) {
            if (!path.toString().endsWith(".jar.bpatch")) continue;
            UFXProtocol.Update.Builder update = UFXProtocol.Update.newBuilder();
            int num = Integer.parseInt(path.getFileName().toString().replaceAll("\\.jar\\.bpatch", ""));
            update.setVersion(num);
            byte[] bits = Files.readAllBytes(path);
            update.setPatchSize(bits.length);
            update.setHash(ByteString.copyFrom(Utils.sha256(bits)));
            System.out.println(path.toString() + ": " + BaseEncoding.base16().encode(update.getHash().toByteArray()));
            for (String baseURL : url.values(options)) {
                try {
                    URI uri = new URI((baseURL.endsWith("/") ? baseURL : baseURL.concat("/")) + num + ".jar.bpatch");
                    update.addUrls(uri.toString());
                } catch (URISyntaxException e) {
                    System.err.println("Base URL is malformed: " + baseURL);
                    return;
                }
            }
            updates.addUpdates(update);
        }
        // Sign it.
        UFXProtocol.SignedUpdates.Builder signedUpdates = UFXProtocol.SignedUpdates.newBuilder();
        byte[] bits = updates.build().toByteArray();
        byte[] hash = Utils.sha256(bits);
        ECKey key = wallet.currentReceiveKey();
        String signature = key.signMessage(BaseEncoding.base16().encode(hash).toLowerCase());
        signedUpdates.addSignatures(signature);
        signedUpdates.setUpdates(ByteString.copyFrom(bits));
        // Save the index to the sites dir
        Files.write(site.resolve("index"), signedUpdates.build().toByteArray());
        System.out.println("Signed with public key " + BaseEncoding.base16().encode(key.getPubKey()));
    }
}
