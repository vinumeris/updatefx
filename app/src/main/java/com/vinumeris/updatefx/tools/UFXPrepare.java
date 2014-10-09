package com.vinumeris.updatefx.tools;

import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;
import com.vinumeris.updatefx.DeltaCalculator;
import com.vinumeris.updatefx.UFXProtocol;
import com.vinumeris.updatefx.Utils;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.store.UnreadableWalletException;
import org.bitcoinj.utils.BriefLogFormatter;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * This app takes a working directory that contains a subdir called "builds", containing each version of the app
 * named like 1.jar, 2.jar, 3.jar etc. It creates in a subdir called "site" a set of patch files and an index.
 * In the working directory it creates a bitcoinj format wallet that holds signing keys. If the jar file contains
 * a file in the root package named "update-description.txt" then the first line is used as the one liner, and the rest
 * is used as the update description.
 */
public class UFXPrepare {
    public static void main(String[] args) throws IOException, UnreadableWalletException {
        OptionParser parser = new OptionParser();
        // Base URL where the patches will be served. Can be specified multiple times.
        OptionSpec<String> url = parser.accepts("url").withRequiredArg();
        parser.accepts("debuglog");
        // If set, which version to start decompressing jars from and applying gzip to the resulting patch files.
        OptionSpec<String> gzipFromStr = parser.accepts("gzip-from").withRequiredArg().defaultsTo("-1");
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

        int gzipFrom = Integer.parseInt(gzipFromStr.value(options));

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
        // Process the jars to remove timestamps and decompress. This does nothing if the zip is already processed.
        // Version ranges can be excluded for compatibility with old Lighthouse versions.
        // TODO: Once all testers are upgraded, remove the backwards compat stuff.
        // Also extract descriptions, if they exist.
        Map<Integer, UFXProtocol.UpdateDescription> descriptions = new HashMap<>();
        for (Path path : Utils.listDir(builds)) {
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                int v = Integer.parseInt(path.getFileName().toString().replace(".jar", ""));
                if (v >= gzipFrom)
                    ProcessZIP.process(path);
                JarFile jar = new JarFile(path.toFile());
                JarEntry entry = jar.getJarEntry("update-description.txt");
                if (entry != null) {
                    try (InputStream stream = jar.getInputStream(entry)) {
                        LineNumberReader reader = new LineNumberReader(new BufferedReader(new InputStreamReader(stream)));
                        String first = reader.readLine();
                        StringBuilder rest = new StringBuilder();
                        String tmp;
                        while ((tmp = reader.readLine()) != null) {
                            rest.append(tmp);
                            rest.append("\n");
                        }
                        UFXProtocol.UpdateDescription desc = UFXProtocol.UpdateDescription.newBuilder()
                                .setOneLiner(first)
                                .setDescription(rest.toString())
                                .build();
                        descriptions.put(v, desc);
                    }
                }
            }
        }

        // Generate the patch files.
        List<DeltaCalculator.Result> patches = DeltaCalculator.process(builds.toAbsolutePath(), site.toAbsolutePath(), gzipFrom);
        // Build an index.
        UFXProtocol.Updates.Builder updates = UFXProtocol.Updates.newBuilder();
        for (DeltaCalculator.Result patch : patches) {
            UFXProtocol.Update.Builder update = UFXProtocol.Update.newBuilder();
            int num = Integer.parseInt(patch.path.getFileName().toString().replaceAll("\\.jar\\.bpatch", ""));
            update.setVersion(num);
            update.setPatchSize(patch.patchSize);
            update.setPreHash(ByteString.copyFrom(patch.preHash));
            update.setPatchHash(ByteString.copyFrom(patch.patchHash));
            update.setPostHash(ByteString.copyFrom(patch.postHash));
            update.setGzipped(num >= gzipFrom);
            for (String baseURL : url.values(options)) {
                try {
                    URI uri = new URI((baseURL.endsWith("/") ? baseURL : baseURL.concat("/")) + num + ".jar.bpatch");
                    update.addUrls(uri.toString());
                } catch (URISyntaxException e) {
                    System.err.println("Base URL is malformed: " + baseURL);
                    return;
                }
            }
            UFXProtocol.UpdateDescription desc = descriptions.get(num);
            if (desc != null)
                update.addDescription(desc);
            updates.addUpdates(update);
        }
        // Sign it.
        updates.setVersion(1);
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
