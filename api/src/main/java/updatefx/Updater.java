package updatefx;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.protobuf.InvalidProtocolBufferException;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

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

    private long totalBytesDownloaded;

    public Updater(String updateBaseURL, String userAgent, int currentVersion, Path localUpdatesDir) {
        this.updateBaseURL = updateBaseURL;
        this.userAgent = userAgent;
        this.currentVersion = currentVersion;
        this.localUpdatesDir = localUpdatesDir;
    }

    @Override
    protected UpdateSummary call() throws Exception {
        UFXProtocol.SignedUpdates signedUpdates = downloadSignedIndex();
        processSignedUpdates(signedUpdates);
        return new UpdateSummary();
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

    private void processSignedUpdates(UFXProtocol.SignedUpdates signedUpdates) throws IOException, URISyntaxException, Ex {
        UFXProtocol.Updates updates = validateSignatures(signedUpdates);
        LinkedList<UFXProtocol.Update> applicableUpdates = new LinkedList<>();
        long bytesToFetch = 0;
        for (UFXProtocol.Update update : updates.getUpdatesList()) {
            if (update.getVersion() > currentVersion) {
                applicableUpdates.add(update);
                bytesToFetch += update.getPatchSize();
            }
        }
        log.info("Found {} applicable updates totalling {} bytes", applicableUpdates.size(), bytesToFetch);
        downloadUpdates(applicableUpdates, bytesToFetch);
    }

    private List<Path> downloadUpdates(LinkedList<UFXProtocol.Update> updates, long bytesToFetch) throws URISyntaxException, IOException, Ex {
        LinkedList<Path> files = new LinkedList<>();
        for (UFXProtocol.Update update : updates) {
            if (update.getUrlsCount() == 0)
                throw new IllegalStateException("Bad update definition: no URLs");
            String url = update.getUrls((int) (update.getUrlsCount() * Math.random()));
            log.info("Downloading update from {}", url);
            URLConnection connection = openURL(new URI(url));
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
                if (!Files.isDirectory(tmpDir))
                    Files.createDirectory(tmpDir);
                Path outfile = tmpDir.resolve(update.getVersion() + ".jar");
                log.info(" ... saving to {}", outfile);
                byte[] sha256;
                try (HashingOutputStream savedFile = hashingFileStream(outfile)) {
                    ByteStreams.copy(stream, savedFile);
                    sha256 = savedFile.hash().asBytes();
                }
                if (Arrays.equals(update.getHash().toByteArray(), sha256)) {
                    files.add(outfile);
                } else {
                    log.error("Downloaded file did not match signed index hash: {} vs {}",
                            BaseEncoding.base16().lowerCase().encode(sha256),
                            BaseEncoding.base16().lowerCase().encode(update.getHash().toByteArray()));
                    throw new Ex.BadUpdateHash();
                }
            }
        }
        return files;
    }

    private HashingOutputStream hashingFileStream(Path outfile) throws IOException {
        return new HashingOutputStream(Hashing.sha256(), new BufferedOutputStream(Files.newOutputStream(outfile)));
    }

    private UFXProtocol.Updates validateSignatures(UFXProtocol.SignedUpdates updates) throws InvalidProtocolBufferException {
        // TODO: Actually validate signatures here.
        return UFXProtocol.Updates.parseFrom(updates.getUpdates());
    }

    public static class Ex extends Exception {
        public static class BadUpdateHash extends Ex {}
    }
}
