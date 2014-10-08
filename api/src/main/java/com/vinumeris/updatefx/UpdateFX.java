package com.vinumeris.updatefx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static java.lang.String.format;

/**
 * Given a directory, select the jar with the highest version number, load it and pass control to it.
 */
public class UpdateFX {
    public static final String VERSION_PIN_FILE_NAME = "version-pin.txt";
    private static Logger log = LoggerFactory.getLogger(UpdateFX.class);

    public static void bootstrap(Class mainClass, Path updatesDirectory, String[] args) {
        try {
            Path codePath = findCodePath(mainClass);
            Path appInstallDir = codePathToInstallDir(codePath);
            if (Files.isDirectory(codePath) || "ignore".equals(System.getProperty("updatefx"))) {
                log.info("Code location is not a JAR: assuming developer mode and ignoring updates.");
                runRealMain(appInstallDir, args, mainClass);
                return;
            }
            Path jar = findRightJar(codePath, updatesDirectory);
            invoke(appInstallDir, mainClass, args, jar);
        } catch (Exception e) {
            log.error("Bootstrap failed", e);
            throw new RuntimeException(e);
        }
    }

    private static Path codePathToInstallDir(Path origJarPath) {
        // origJarPath contains the JAR that the user originally installed, before any updates, and therefore the
        // app install directory which contains our JRE. Here we calculate the root of this directory, which is platform
        // dependent, so later on we can go from the install dir -> find the JRE again. This is useful for automated
        // restarts.
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return origJarPath.getParent() /* c:\Users\foo\AppData\Local\AppName\app */.getParent();
        } else if (os.contains("mac")) {
            // Returns path to the .app directory, which normally contains only a Contents directory.
            return origJarPath.getParent().resolve("../../").normalize().toAbsolutePath();
        } else {
            // Linux and other similar systems, we hope (not Android).
            return origJarPath.getParent() /* /opt/appname/app */ .getParent();
        }
    }

    /**
     * Tries to find the JVM assuming the layout used by javafxpackager. Then execute it in a new process and quit
     * this process. We have to do it this way because JavaFX is designed to only be started once and there's no
     * easy way to hack around that: we need to tear down the entire process and start again.
     */
    public static void restartApp() {
        try {
            Path path = findAppExecutable(appInstallDir);
            if (path == null) throw new UnsupportedOperationException();
            String[] cmd = new String[initArgs.length + 1];
            System.arraycopy(initArgs, 0, cmd, 1, initArgs.length);
            cmd[0] = path.toAbsolutePath().toString();
            log.info("Restarting app with command line: {}", Arrays.toString(cmd));
            new ProcessBuilder(cmd).start();
            Runtime.getRuntime().exit(0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path findAppExecutable(Path appInstallDir) throws IOException {
        if (appInstallDir == null)
            throw new NullPointerException();
        // TODO: It'd be nice if there was a better way to find our native executable path.
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            // Returns path to the .app directory, which normally contains only a Contents directory.
            List<Path> exes = Utils.listDir(appInstallDir.resolve("Contents/MacOS"));
            if (exes.size() != 1)
                throw new IllegalStateException("Found unknown number of app executables");
            return exes.get(0);
        } else {
            // Linux, Windows and other similar systems, we hope (not Android).
            // Binary is in the top level of the app installd dir (/opt/appname/AppName) along with a few other files.
            // So we locate the binary by finding the program that is marked executable and isn't a directory.
            List<Path> exes = Utils.listDir(appInstallDir);
            List<Path> orig = new LinkedList<>(exes);
            if (os.contains("win")) {
                exes.removeIf(path -> !path.toString().toLowerCase().endsWith(".exe"));
                exes.removeIf(path -> path.toString().toLowerCase().endsWith("unins000.exe"));
            } else {
                exes.removeIf(path -> !Files.isExecutable(path) || path.getFileName().toString().contains(".") || Files.isDirectory(path));
            }
            if (exes.size() != 1)
                throw new IllegalStateException("App install dir didn't look like what we expected: " + orig + " vs " + exes);
            return exes.get(0);
        }
    }

    private static void invoke(Path appInstallDir, Class mainClass, String[] args, Path bestJarSeen) throws Exception {
        URL url = bestJarSeen.toUri().toURL();
        URLClassLoader classLoader = new URLClassLoader(new URL[] { url }, UpdateFX.class.getClassLoader().getParent());
        Class<?> newClass = Class.forName(mainClass.getCanonicalName(), true, classLoader);
        Thread.currentThread().setContextClassLoader(classLoader);
        runRealMain(appInstallDir, args, newClass);
        classLoader.close();
    }

    public static Path appInstallDir;
    public static String[] initArgs;

    private static void runRealMain(Path appInstallDir, String[] args, Class<?> newClass) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, ClassNotFoundException, NoSuchFieldException {
        Class<?> thisStatic = Class.forName(UpdateFX.class.getCanonicalName(), true, Thread.currentThread().getContextClassLoader());
        thisStatic.getField("appInstallDir").set(null, appInstallDir);
        thisStatic.getField("initArgs").set(null, args);
        // We can't cast to EntryPoint here because it's loaded in a parallel classloader heirarchy.
        Method main = newClass.getMethod("realMain", String[].class);
        main.invoke(null, (Object) args);   // The apparently pointless cast is to disambiguate things for the compiler.
    }

    public static Path findCodePath(Class mainClass) {
        try {
            return Paths.get(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);   // Should never happen.
        }
    }

    /**
     * Writes a text file to the app install dir containing the given version number. It is read at startup and prevents
     * the highest version from being used.
     */
    public static void pinToVersion(Path updatesDirectory, int version) {
        try {
            Path pinPath = updatesDirectory.resolve(VERSION_PIN_FILE_NAME);
            Files.write(pinPath, String.valueOf(version).getBytes(), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns 0 if not pinned. */
    public static int getVersionPin(Path updatesDirectory) {
        try {
            Path pinPath = updatesDirectory.resolve(VERSION_PIN_FILE_NAME);
            if (Files.exists(pinPath))
                return Integer.parseInt(Files.readAllLines(pinPath).get(0));
            else
                return 0;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path findRightJar(Path origJarPath, Path updatesDirectory) throws IOException {
        Path pinPath = updatesDirectory.resolve(VERSION_PIN_FILE_NAME);
        if (Files.exists(pinPath)) {
            try {
                String contents = Files.readAllLines(pinPath).get(0);
                int version = Integer.parseInt(contents);
                String origVersionFN = origJarPath.getFileName().toString();
                int origVersion = Integer.parseInt(origVersionFN.substring(0, origVersionFN.length() - 4));
                Path pinnedJar = updatesDirectory.resolve(format("%d.jar", version));
                if (Files.exists(pinnedJar)) {
                    return pinnedJar;
                } else if (version == origVersion) {
                    return origJarPath;
                } else if (version < origVersion) {
                    throw new IllegalStateException();
                }
            } catch (Exception e) {
                log.error("Could not parse/read " + pinPath + ", ignoring", e);
            }
        }
        return findBestJar(origJarPath, updatesDirectory);
    }

    private static Path findBestJar(Path origJarPath, Path updatesDirectory) throws IOException {
        if (!Files.isDirectory(updatesDirectory))
            throw new IllegalArgumentException("Not a directory: " + updatesDirectory);
        int bestUpdateSeen = -1;
        Path bestJarSeen = null;
        for (Path path : Utils.listDir(updatesDirectory)) {
            if (path.getFileName().toString().endsWith(".jar")) {
                log.info("Considering {} for bootstrap", path);
                String fn = path.getFileName().toString();
                fn = fn.substring(0, fn.length() - 4);  // Strip extension.
                try {
                    int n = Integer.parseInt(fn);
                    if (n > bestUpdateSeen) {
                        bestUpdateSeen = n;
                        bestJarSeen = path;
                    }
                } catch (NumberFormatException e) {
                    log.warn("JAR didn't meet naming criteria: {}", fn);
                }
            }
        }
        if (bestJarSeen == null)
            bestJarSeen = origJarPath;
        return bestJarSeen;
    }
}
