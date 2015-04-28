package com.vinumeris.updatefx;

import com.google.common.hash.*;
import com.google.protobuf.*;
import com.sun.net.httpserver.*;
import org.bouncycastle.math.ec.*;
import org.junit.*;

import java.io.*;
import java.math.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import static com.vinumeris.updatefx.Utils.*;
import static java.net.HttpURLConnection.*;
import static java.nio.file.Files.*;
import static java.nio.file.StandardOpenOption.*;
import static org.junit.Assert.*;

public class UpdaterTest {
    private static final int HTTP_LOCAL_TEST_PORT = 18475;
    public static final String SERVER_PATH = "/_updatefx/appname";
    private HttpServer localServer;
    private Map<String, byte[]> paths;
    private Updater updater;
    private Path dir;
    private URI indexURL;

    private long workDone, workMax;

    private List<BigInteger> privKeys;
    private List<ECPoint> pubKeys;

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

        dir = createTempDirectory("updatefx");
        indexURL = URI.create("http://localhost:" + HTTP_LOCAL_TEST_PORT + SERVER_PATH + "/index");

        privKeys = new LinkedList<>();
        SecureRandom rnd = new SecureRandom();
        for (int i = 0; i < 3; i++) {
            privKeys.add(new BigInteger(256, rnd));
        }
        pubKeys = Crypto.privsToPubs(privKeys);
    }

    public class TestUpdater extends Updater {
        public TestUpdater(URI indexURL, String userAgent, Path localUpdatesDir, Path pathToOrigJar) {
            super(indexURL, userAgent, localUpdatesDir, pathToOrigJar == null ? Paths.get("/1.jar") : pathToOrigJar, pubKeys, 2);
        }

        @Override
        protected void updateProgress(long workDone, long max) {
            UpdaterTest.this.workDone = workDone;
            UpdaterTest.this.workMax = max;
        }
    }

    @After
    public void tearDown() throws Exception {
        localServer.stop(Integer.MAX_VALUE);
    }

    @Test(expected = FileNotFoundException.class)
    public void test404ForIndex() throws Exception {
        updater = new TestUpdater(indexURL, "UnitTest", dir, null);
        updater.call();
    }

    private void configureIndex(byte[]... hashes) {
        UFXProtocol.SignedUpdates.Builder signedUpdates = buildIndex(hashes);
        paths.put("/index", signedUpdates.build().toByteArray());
    }

    private UFXProtocol.SignedUpdates.Builder buildIndex(byte[]... hash) {
        UFXProtocol.SignedUpdates.Builder signedUpdates = UFXProtocol.SignedUpdates.newBuilder();
        UFXProtocol.Updates.Builder updates = UFXProtocol.Updates.newBuilder();
        updates.setVersion(1);
        int verCursor = 2;
        for (int i = 0; i < hash.length; i++) {
            UFXProtocol.Update.Builder update = UFXProtocol.Update.newBuilder();
            update.setVersion(verCursor);
            String serverPath = "/" + verCursor + ".jar.bpatch";
            verCursor++;
            update.addUrls("http://localhost:" + HTTP_LOCAL_TEST_PORT + SERVER_PATH + serverPath);
            update.setPreHash(ByteString.copyFrom(hash[i++]));
            update.setPatchHash(ByteString.copyFrom(hash[i++]));
            update.setPostHash(ByteString.copyFrom(hash[i]));
            if (paths.get(serverPath) != null)
                update.setPatchSize(paths.get(serverPath).length);
            else
                update.setPatchSize(0);
            update.setGzipped(true);
            updates.addUpdates(update);
        }
        ByteString bytes = updates.build().toByteString();
        signedUpdates.setUpdates(bytes);
        String message = Hashing.sha256().hashBytes(bytes.toByteArray()).toString();
        signedUpdates.addSignatures(Crypto.signMessage(message, privKeys.get(0)));
        signedUpdates.addSignatures(Crypto.signMessage(message, privKeys.get(1)));
        return signedUpdates;
    }

    @Test
    public void test404ForUpdateFile() throws Exception {
        byte[] b = "ignored".getBytes();
        configureIndex(b, b, b);
        try {
            updater = new TestUpdater(indexURL, "UnitTest", dir, null);
            updater.call();
            fail();
        } catch (FileNotFoundException e) {
            assertEquals("http://localhost:18475/_updatefx/appname/2.jar.bpatch", e.getMessage());
        }
    }

    @Test(expected = Updater.Ex.BadUpdateHash.class)
    public void patchFailsHashCheck() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar.bpatch", fakePatch);
        byte[] b = "wrong".getBytes();
        configureIndex(b, b, b);
        updater = new TestUpdater(indexURL, "UnitTest", dir, null);
        updater.call();
    }

    @Test(expected = Updater.Ex.InsufficientSigners.class)
    public void insufficientSigs() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar.bpatch", fakePatch);
        UFXProtocol.SignedUpdates.Builder builder = makeWrongIndex();
        builder.clearSignatures();
        paths.put("/index", builder.build().toByteArray());
        updater = new TestUpdater(indexURL, "UnitTest", dir, null);
        updater.call();
    }

    private UFXProtocol.SignedUpdates.Builder makeWrongIndex() {
        byte[] b = "wrong".getBytes();
        return buildIndex(b, b, b);
    }

    @Test(expected = Updater.Ex.InsufficientSigners.class)
    public void unknownSig() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar.bpatch", fakePatch);
        UFXProtocol.SignedUpdates.Builder builder = makeWrongIndex();
        BigInteger evilKey = new BigInteger(256, new SecureRandom());
        builder.setSignatures(0, Crypto.signMessage("msg", evilKey));
        paths.put("/index", builder.build().toByteArray());
        updater = new TestUpdater(indexURL, "UnitTest", dir, null);
        updater.call();
    }

    @Test(expected = Updater.Ex.InsufficientSigners.class)
    public void replayedSig() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar.bpatch", fakePatch);
        UFXProtocol.SignedUpdates.Builder builder = makeWrongIndex();
        builder.setSignatures(0, Crypto.signMessage("hash from some other project", privKeys.get(0)));
        paths.put("/index", builder.build().toByteArray());
        updater = new TestUpdater(indexURL, "UnitTest", dir, null);
        updater.call();
    }

    @Test(expected = SignatureException.class)
    public void badSig() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar.bpatch", fakePatch);
        UFXProtocol.SignedUpdates.Builder builder = makeWrongIndex();
        builder.setSignatures(0, "bzzzz");
        paths.put("/index", builder.build().toByteArray());
        updater = new TestUpdater(indexURL, "UnitTest", dir, null);
        updater.call();
    }

    @Test
    public void updateRun() throws Exception {
        Path working = dir.resolve("working");
        createDirectory(working);
        byte[] baseFile = new byte[2048];
        Arrays.fill(baseFile, (byte) 1);
        Path baseJar = working.resolve("1.jar");
        write(baseJar, baseFile, CREATE_NEW);
        baseFile[0] = 2;
        Path jar2 = working.resolve("2.jar");
        write(jar2, baseFile, CREATE_NEW);
        baseFile[0] = 3;
        Path jar3 = working.resolve("3.jar");
        write(jar3, baseFile, CREATE_NEW);
        DeltaCalculator.process(working.toAbsolutePath(), working.toAbsolutePath(), -1);
        Path bpatch2 = working.resolve("2.jar.bpatch");
        assertTrue(exists(bpatch2));
        Path bpatch3 = working.resolve("3.jar.bpatch");
        assertTrue(exists(bpatch3));
        byte[] bpatch1bits = readAllBytes(bpatch2);
        byte[] bpatch2bits = readAllBytes(bpatch3);
        paths.put("/2.jar.bpatch", bpatch1bits);
        paths.put("/3.jar.bpatch", bpatch2bits);
        configureIndex(sha256(readAllBytes(baseJar)), sha256(bpatch1bits), sha256(readAllBytes(jar2)),
                       sha256(readAllBytes(jar2)), sha256(bpatch2bits), sha256(readAllBytes(jar3)));
        updater = new TestUpdater(indexURL, "UnitTest", dir, baseJar);
        UpdateSummary summary = updater.call();
        assertEquals(80, workDone);
        assertEquals(80, workMax);
        byte[] bits3 = Files.readAllBytes(dir.resolve("3.jar"));
        assertArrayEquals(baseFile, bits3);
        assertEquals(3, summary.highestVersion);
    }

    @Test
    public void updateRun2() throws Exception {
        // Update from v2 to v3.
        Path working = dir.resolve("working");
        createDirectory(working);
        byte[] baseFile = new byte[2048];
        Arrays.fill(baseFile, (byte) 1);
        Path jar1 = working.resolve("1.jar");
        write(jar1, baseFile, CREATE_NEW);
        baseFile[0] = 2;
        Path baseJar = working.resolve("2.jar");
        write(baseJar, baseFile, CREATE_NEW);
        baseFile[0] = 3;
        Path jar3 = working.resolve("3.jar");
        write(jar3, baseFile, CREATE_NEW);
        DeltaCalculator.process(working.toAbsolutePath(), working.toAbsolutePath(), -1);
        Path bpatch2 = working.resolve("2.jar.bpatch");
        assertTrue(exists(bpatch2));
        Path bpatch3 = working.resolve("3.jar.bpatch");
        assertTrue(exists(bpatch3));
        byte[] bpatch1bits = readAllBytes(bpatch2);
        byte[] bpatch2bits = readAllBytes(bpatch3);
        paths.put("/2.jar.bpatch", bpatch1bits);
        paths.put("/3.jar.bpatch", bpatch2bits);
        configureIndex(sha256(readAllBytes(jar1)), sha256(bpatch1bits), sha256(readAllBytes(baseJar)),
                       sha256(readAllBytes(baseJar)), sha256(bpatch2bits), sha256(readAllBytes(jar3)));
        updater = new TestUpdater(indexURL, "UnitTest", dir, baseJar);
        UpdateSummary summary = updater.call();
        byte[] bits3 = Files.readAllBytes(dir.resolve("3.jar"));
        assertArrayEquals(baseFile, bits3);
        assertEquals(3, summary.highestVersion);
    }

    @Test
    public void updateRunWithPinning() throws Exception {
        // Update from v2 to v3, whilst we are pinned to v2.
        Path working = dir.resolve("working");
        createDirectory(working);
        byte[] baseFile = new byte[2048];
        Arrays.fill(baseFile, (byte) 1);
        Path jar1 = working.resolve("1.jar");
        write(jar1, baseFile, CREATE_NEW);
        baseFile[0] = 2;
        Path baseJar = working.resolve("2.jar");
        write(baseJar, baseFile, CREATE_NEW);
        baseFile[0] = 3;
        Path jar3 = working.resolve("3.jar");
        write(jar3, baseFile, CREATE_NEW);
        DeltaCalculator.process(working.toAbsolutePath(), working.toAbsolutePath(), -1);
        Path bpatch2 = working.resolve("2.jar.bpatch");
        assertTrue(exists(bpatch2));
        Path bpatch3 = working.resolve("3.jar.bpatch");
        assertTrue(exists(bpatch3));
        byte[] bpatch1bits = readAllBytes(bpatch2);
        byte[] bpatch2bits = readAllBytes(bpatch3);
        paths.put("/2.jar.bpatch", bpatch1bits);
        paths.put("/3.jar.bpatch", bpatch2bits);
        configureIndex(sha256(readAllBytes(jar1)), sha256(bpatch1bits), sha256(readAllBytes(baseJar)),
                sha256(readAllBytes(baseJar)), sha256(bpatch2bits), sha256(readAllBytes(jar3)));

        UpdateFX.pinToVersion(dir, 2);
        UpdateFX.pinToVersion(dir, 1);

        updater = new TestUpdater(indexURL, "UnitTest", dir, baseJar);
        UpdateSummary summary = updater.call();
        byte[] bits3 = Files.readAllBytes(dir.resolve("3.jar"));
        assertArrayEquals(baseFile, bits3);
        assertEquals(3, summary.highestVersion);

        UpdateFX.unpin(dir);
    }

    @Test
    public void testBaseURLOverride() throws Exception {
        indexURL = URI.create("https://www.example.com/updates/index");
        // Check the 404 for the update file is obtained (i.e. we made contact with the local server).
        byte[] b = "ignored".getBytes();
        configureIndex(b, b, b);
        try {
            updater = new TestUpdater(URI.create("http://localhost:18475/_updatefx/appname/index"), "UnitTest", dir, null);
            updater.setOverrideURLs(true);
            updater.call();
            fail();
        } catch (FileNotFoundException e) {
            assertEquals("http://localhost:18475/_updatefx/appname/2.jar.bpatch", e.getMessage());
        }
    }
}