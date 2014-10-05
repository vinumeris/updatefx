package com.vinumeris.updatefx;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.protobuf.InvalidProtocolBufferException;
import com.nothome.delta.GDiffPatcher;
import com.nothome.delta.RandomAccessFileSeekableSource;
import javafx.concurrent.Task;
import org.bouncycastle.math.ec.ECPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SignatureException;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static com.google.common.base.Preconditions.checkState;
import static com.vinumeris.updatefx.Utils.sha256;
import static java.nio.file.Files.*;

/**
 * An updater does all the work of downloading, checking and applying updates on a background thread. It has
 * properties that can be bound to monitor the state of the operations taking place.
 */
public class Updater extends Task<UpdateSummary> {
    private static final Logger log = LoggerFactory.getLogger(Updater.class);

    private final String updateBaseURL;
    private final String userAgent;
    private final int currentVersion;
    private final Path localUpdatesDir;
    private final Path pathToOrigJar;
    private final List<ECPoint> pubkeys;
    private final int requiredSigningThreshold;

    private long totalBytesDownloaded;
    private int newHighestVersion;
    private boolean overrideURLs = false;

    public Updater(String updateBaseURL, String userAgent, int currentVersion, Path localUpdatesDir,
                   Path pathToOrigJar, List<ECPoint> pubkeys, int requiredSigningThreshold) {
        this.updateBaseURL = updateBaseURL.endsWith("/") ? updateBaseURL.substring(0, updateBaseURL.length() - 1) : updateBaseURL;
        this.userAgent = userAgent;
        this.currentVersion = currentVersion;
        this.localUpdatesDir = localUpdatesDir;
        this.pathToOrigJar = pathToOrigJar;
        this.pubkeys = pubkeys;
        this.requiredSigningThreshold = requiredSigningThreshold;

        newHighestVersion = currentVersion;
    }

    /**
     * If true, then any URLs found in the index file will be ignored, and their file name will be appended to the
     * updateBaseURL instead. This is useful when you wish to test a new index locally before uploading it to your
     * web server: by setting an updateBaseURL of localhost and setting this to true, updates will be downloaded
     * from a local web server instead.
     */
    public void setOverrideURLs(boolean overrideURLs) {
        this.overrideURLs = overrideURLs;
    }

    @Override
    protected UpdateSummary call() throws Exception {
        UFXProtocol.SignedUpdates signedUpdates = downloadSignedIndex();
        processSignedIndex(signedUpdates);
        return new UpdateSummary(newHighestVersion);
    }

    private UFXProtocol.SignedUpdates downloadSignedIndex() throws IOException, URISyntaxException {
        URI url = new URI(updateBaseURL + "/index");
        log.info("Requesting " + url);
        URLConnection connection = openURL(url);
        // Limit to 10mb in case something weird happens and we get an infinite stream of junk. 10mb of update
        // metadata is wildly excessive anyway.
        return UFXProtocol.SignedUpdates.parseFrom(ByteStreams.limit(connection.getInputStream(), 10 * 1024 * 1024));
    }

    private URLConnection openURL(URI url) throws IOException {
        URLConnection connection = url.toURL().openConnection();
        connection.setDoOutput(true);
        connection.setConnectTimeout(10 * 1000);
        connection.addRequestProperty("User-Agent", userAgent);
        connection.connect();
        return connection;
    }

    private void processSignedIndex(UFXProtocol.SignedUpdates signedUpdates) throws IOException, URISyntaxException, Ex, SignatureException {
        UFXProtocol.Updates updates = validateSignatures(signedUpdates);
        if (updates.getVersion() != 1)
            throw new Ex.UnknownIndexVersion();
        LinkedList<UFXProtocol.Update> applicableUpdates = new LinkedList<>();
        long bytesToFetch = 0;
        for (UFXProtocol.Update update : updates.getUpdatesList()) {
            if (update.getVersion() > currentVersion) {
                applicableUpdates.add(update);
                bytesToFetch += update.getPatchSize();
            }
        }
        if (applicableUpdates.isEmpty()) {
            log.info("No updates found: we're fresh!");
        } else {
            log.info("Found {} applicable updates totalling {} bytes", applicableUpdates.size(), bytesToFetch);
            List<Path> downloadedUpdates = downloadUpdates(applicableUpdates, bytesToFetch);
            processDownloadedUpdates(applicableUpdates, downloadedUpdates);
        }
    }

    private List<Path> downloadUpdates(LinkedList<UFXProtocol.Update> updates, long bytesToFetch) throws URISyntaxException, IOException, Ex {
        LinkedList<Path> files = new LinkedList<>();
        if (updates.isEmpty()) return files;
        Updater.this.updateProgress(0, bytesToFetch);
        for (UFXProtocol.Update update : updates) {
            if (update.getUrlsCount() == 0)
                throw new IllegalStateException("Bad update definition: no URLs");
            URI url = new URI(update.getUrls((int) (update.getUrlsCount() * Math.random())));
            url = maybeOverrideBaseURL(url);
            log.info("Downloading update from {}", url);
            URLConnection connection = openURL(url);
            long size = connection.getContentLengthLong();
            long initialBytesRead = totalBytesDownloaded;
            try (InputStream netStream = connection.getInputStream()) {
                BufferedInputStream bufStream = new BufferedInputStream(netStream);
                ProgressCalculatingStream stream = new ProgressCalculatingStream(bufStream, size) {
                    @Override
                    protected void updateProgress(long readSoFar, long expectedBytes, double progress) {
                        log.info(String.format("Download progress: %.2f%%", progress  * 100));
                        totalBytesDownloaded = initialBytesRead + readSoFar;
                        // Marshal to UI thread.
                        Updater.this.updateProgress(totalBytesDownloaded, bytesToFetch);
                    }
                };
                Path tmpDir = localUpdatesDir.resolve("tmp");
                if (!isDirectory(tmpDir))
                    createDirectory(tmpDir);
                Path outfile = tmpDir.resolve(update.getVersion() + ".jar.bpatch");
                deleteIfExists(outfile);
                log.info(" ... saving to {}", outfile);
                byte[] sha256;
                try (HashingOutputStream savedFile = hashingFileStream(outfile)) {
                    ByteStreams.copy(stream, savedFile);
                    sha256 = savedFile.hash().asBytes();
                }
                if (Arrays.equals(update.getPatchHash().toByteArray(), sha256)) {
                    files.add(outfile);
                } else {
                    log.error("Downloaded file did not match signed index hash: {} vs {}",
                            BaseEncoding.base16().lowerCase().encode(sha256),
                            BaseEncoding.base16().lowerCase().encode(update.getPatchHash().toByteArray()));
                    throw new Ex.BadUpdateHash();
                }
            }
        }
        return files;
    }

    private void processDownloadedUpdates(List<UFXProtocol.Update> updates, List<Path> files) throws IOException, Ex.BadUpdateHash {
        // Go through the list and apply each patch (it's an xdelta) to the previous version to create a new full
        // blown JAR, which is then moved into the updates base dir. The first update is special and is applied
        // to the base jar that came with the downloaded app.
        int cursor = 0;
        for (Path path : files) {
            UFXProtocol.Update update = updates.get(cursor);
            Path base = pathToOrigJar;
            if (update.getVersion() > currentVersion + 1)
                base = localUpdatesDir.resolve((update.getVersion() - 1) + ".jar");
            Path next = localUpdatesDir.resolve(update.getVersion() + ".jar");
            log.info("Applying patch {} to {}", path, base);
            // By here the patch hash was verified, but not the pre/post hashes.
            byte[] preHash = sha256(readAllBytes(base));
            if (!Arrays.equals(preHash, update.getPreHash().toByteArray()))
                throw new Ex.BadUpdateHash();
            if (update.getGzipped()) {
                try (RandomAccessFileSeekableSource baseSource = new RandomAccessFileSeekableSource(new RandomAccessFile(base.toFile(), "r"));
                     InputStream patchStream = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path)));
                     OutputStream nextStream = new BufferedOutputStream(Files.newOutputStream(next))) {
                    new GDiffPatcher().patch(baseSource, patchStream, nextStream);
                }
            } else {
                new GDiffPatcher().patch(base.toFile(), path.toFile(), next.toFile());
            }
            byte[] postHash = sha256(readAllBytes(next));
            if (!Arrays.equals(postHash, update.getPostHash().toByteArray()))
                throw new Ex.BadUpdateHash();
            checkState(update.getVersion() > newHighestVersion);
            newHighestVersion = update.getVersion();
            cursor++;
        }
    }

    private URI maybeOverrideBaseURL(URI url) throws URISyntaxException {
        if (!overrideURLs) return url;

        String[] split = url.getPath().split("/");
        return new URI(updateBaseURL + "/" + split[split.length - 1]).normalize();
    }

    private HashingOutputStream hashingFileStream(Path outfile) throws IOException {
        return new HashingOutputStream(Hashing.sha256(), new BufferedOutputStream(newOutputStream(outfile)));
    }

    private UFXProtocol.Updates validateSignatures(UFXProtocol.SignedUpdates updates) throws Ex, InvalidProtocolBufferException, SignatureException {
        String message = Hashing.sha256().hashBytes(updates.getUpdates().toByteArray()).toString();
        int validSigs = 0;
        Set<ECPoint> keys = new HashSet<>(pubkeys);
        for (String s : updates.getSignaturesList()) {
            ECPoint keyFound = Crypto.signedMessageToKey(message, s);
            if (keyFound != null) {
                if (keys.contains(keyFound)) {
                    keys.remove(keyFound);   // Don't allow the same key to sign more than once.
                    validSigs++;
                } else {
                    log.warn("Found signature by unrecognised key: {}", keyFound);
                }
            }
        }
        if (validSigs >= requiredSigningThreshold)
            return UFXProtocol.Updates.parseFrom(updates.getUpdates());
        else
            throw new Ex.InsufficientSigners();
    }

    public static class Ex extends Exception {
        public static class BadUpdateHash extends Ex {}
        public static class InsufficientSigners extends Ex {}
        public static class UnknownIndexVersion extends Ex {}
    }
}
