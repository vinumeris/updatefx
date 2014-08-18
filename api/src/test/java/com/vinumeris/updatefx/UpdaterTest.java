package com.vinumeris.updatefx;

import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.nio.file.Files.*;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static org.junit.Assert.*;

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
        baseURL = "http://localhost:" + HTTP_LOCAL_TEST_PORT + SERVER_PATH;

        privKeys = new LinkedList<>();
        SecureRandom rnd = new SecureRandom();
        for (int i = 0; i < 3; i++) {
            privKeys.add(new BigInteger(256, rnd));
        }
        pubKeys = Crypto.privsToPubs(privKeys);
    }

    public class TestUpdater extends Updater {
        public TestUpdater(String updateBaseURL, String userAgent, int currentVersion, Path localUpdatesDir, Path pathToOrigJar) {
            super(updateBaseURL, userAgent, currentVersion, localUpdatesDir, pathToOrigJar, pubKeys, 2);
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
        updater = new TestUpdater(baseURL, "UnitTest", 1, dir, null);
        updater.call();
    }

    private void configureIndex(byte[]... hash) {
        UFXProtocol.SignedUpdates.Builder signedUpdates = buildIndex(hash);
        paths.put("/index", signedUpdates.build().toByteArray());
    }

    private UFXProtocol.SignedUpdates.Builder buildIndex(byte[]... hash) {
        UFXProtocol.SignedUpdates.Builder signedUpdates = UFXProtocol.SignedUpdates.newBuilder();
        UFXProtocol.Updates.Builder updates = UFXProtocol.Updates.newBuilder();
        for (int i = 0; i < hash.length; i++) {
            UFXProtocol.Update.Builder update = UFXProtocol.Update.newBuilder();
            update.setVersion(i + 2);
            String serverPath = "/" + (i + 2) + ".jar.bpatch";
            update.addUrls(baseURL + serverPath);
            update.setHash(ByteString.copyFrom(hash[i]));
            if (paths.get(serverPath) != null)
                update.setPatchSize(paths.get(serverPath).length);
            else
                update.setPatchSize(0);
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
        configureIndex("ignored".getBytes());
        try {
            updater = new TestUpdater(baseURL, "UnitTest", 1, dir, null);
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
        configureIndex("wrong".getBytes());
        updater = new TestUpdater(baseURL, "UnitTest", 1, dir, null);
        updater.call();
    }

    @Test(expected = Updater.Ex.InsufficientSigners.class)
    public void insufficientSigs() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar.bpatch", fakePatch);
        UFXProtocol.SignedUpdates.Builder builder = buildIndex("wrong".getBytes());
        builder.clearSignatures();
        paths.put("/index", builder.build().toByteArray());
        updater = new TestUpdater(baseURL, "UnitTest", 1, dir, null);
        updater.call();
    }

    @Test(expected = Updater.Ex.InsufficientSigners.class)
    public void unknownSig() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar.bpatch", fakePatch);
        UFXProtocol.SignedUpdates.Builder builder = buildIndex("wrong".getBytes());
        BigInteger evilKey = new BigInteger(256, new SecureRandom());
        builder.setSignatures(0, Crypto.signMessage("msg", evilKey));
        paths.put("/index", builder.build().toByteArray());
        updater = new TestUpdater(baseURL, "UnitTest", 1, dir, null);
        updater.call();
    }

    @Test(expected = Updater.Ex.InsufficientSigners.class)
    public void replayedSig() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar.bpatch", fakePatch);
        UFXProtocol.SignedUpdates.Builder builder = buildIndex("wrong".getBytes());
        builder.setSignatures(0, Crypto.signMessage("hash from some other project", privKeys.get(0)));
        paths.put("/index", builder.build().toByteArray());
        updater = new TestUpdater(baseURL, "UnitTest", 1, dir, null);
        updater.call();
    }

    @Test(expected = SignatureException.class)
    public void badSig() throws Exception {
        byte[] fakePatch = new byte[1024];
        Arrays.fill(fakePatch, (byte) 0x42);
        paths.put("/2.jar.bpatch", fakePatch);
        UFXProtocol.SignedUpdates.Builder builder = buildIndex("wrong".getBytes());
        builder.setSignatures(0, "bzzzz");
        paths.put("/index", builder.build().toByteArray());
        updater = new TestUpdater(baseURL, "UnitTest", 1, dir, null);
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
        write(working.resolve("2.jar"), baseFile, CREATE_NEW);
        baseFile[0] = 3;
        write(working.resolve("3.jar"), baseFile, CREATE_NEW);
        DeltaCalculator.main(new String[]{working.toAbsolutePath().toString()});
        Path bpatch2 = working.resolve("2.jar.bpatch");
        assertTrue(exists(bpatch2));
        Path bpatch3 = working.resolve("3.jar.bpatch");
        assertTrue(exists(bpatch3));
        byte[] bpatch1bits = readAllBytes(bpatch2);
        byte[] bpatch2bits = readAllBytes(bpatch3);
        paths.put("/2.jar.bpatch", bpatch1bits);
        paths.put("/3.jar.bpatch", bpatch2bits);
        configureIndex(Utils.sha256(bpatch1bits), Utils.sha256(bpatch2bits));
        updater = new TestUpdater(baseURL, "UnitTest", 1, dir, baseJar);
        updater.call();
        assertEquals(1064, workDone);
        assertEquals(1064, workMax);
        byte[] bits3 = Files.readAllBytes(dir.resolve("3.jar"));
        assertArrayEquals(baseFile, bits3);
    }
}