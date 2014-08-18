package updatefx;

import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class UpdaterTest {
    private static final int HTTP_LOCAL_TEST_PORT = 18475;
    public static final String SERVER_PATH = "/_updatefx/appname";
    private HttpServer localServer;
    private LinkedBlockingQueue<HttpExchange> httpReqs;
    private Map<String, byte[]> paths;
    private Updater updater;
    private Path dir;
    private String baseURL;

    private long workDone, workMax;

    @Before
    public void setUp() throws Exception {
        // Create a local HTTP server.
        paths = new HashMap<>();
        localServer = HttpServer.create(new InetSocketAddress("localhost", HTTP_LOCAL_TEST_PORT), 100);
        localServer.createContext(SERVER_PATH, exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(SERVER_PATH)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            path = path.substring(SERVER_PATH.length());
            byte[] bits = paths.get(path);
            if (bits == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(HTTP_OK, bits.length);
            exchange.getResponseBody().write(bits);
            exchange.getResponseBody().close();
        });
        localServer.start();

        dir = Files.createTempDirectory("updatefx");
        baseURL = "http://localhost:" + HTTP_LOCAL_TEST_PORT + SERVER_PATH;
        updater = new Updater(baseURL, "UnitTest", 1, dir) {
            @Override
            protected void updateProgress(long workDone, long max) {
                UpdaterTest.this.workDone = workDone;
                UpdaterTest.this.workMax = max;
            }
        };
    }

    @After
    public void tearDown() throws Exception {
        localServer.stop(Integer.MAX_VALUE);
    }

    @Test(expected = FileNotFoundException.class)
    public void test404ForIndex() throws Exception {
        UpdateSummary summary = updater.call();
    }

    private void configureIndex(byte[] hash) {
        UFXProtocol.SignedUpdates.Builder signedUpdates = UFXProtocol.SignedUpdates.newBuilder();
        UFXProtocol.Updates.Builder updates = UFXProtocol.Updates.newBuilder();
        UFXProtocol.Update.Builder update = UFXProtocol.Update.newBuilder();
        update.setVersion(2);
        update.addUrls(baseURL + "/2.jar");
        update.setHash(ByteString.copyFrom(hash));
        update.setPatchSize(1024);
        updates.addUpdates(update);
        signedUpdates.setUpdates(updates.build().toByteString());
        paths.put("/index", signedUpdates.build().toByteArray());
    }

    @Test
    public void test404ForUpdateFile() throws Exception {
        configureIndex("ignored".getBytes());
        try {
            updater.call();
            fail();
        } catch (FileNotFoundException e) {
            assertEquals("http://localhost:18475/_updatefx/appname/2.jar", e.getMessage());
        }
    }

    @Test(expected = Updater.Ex.BadUpdateHash.class)
    public void patchFailsHashCheck() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar", fakePatch);
        configureIndex("wrong".getBytes());
        updater.call();
    }

    @Test
    public void patchDownloadsOK() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar", fakePatch);
        configureIndex(Hashing.sha256().hashBytes(fakePatch).asBytes());
        updater.call();
    }
}