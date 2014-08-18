package updatefx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Given a directory, select the jar with the highest version number, load it and pass control to it.
 */
public class Bootstrap {
    private static Logger log = LoggerFactory.getLogger(Bootstrap.class);

    @SuppressWarnings("InfiniteLoopStatement")
    public static void bootstrap(Class mainClass, Path updatesDirectory, String[] args) {
        try {
            Path codePath = findCodePath(mainClass);
            if (Files.isDirectory(codePath)) {
                log.info("Code location is not a JAR: assuming developer mode and ignoring updates.");
                runBlockingMain(args, mainClass);
            }
            while (true) {
                Path bestJarSeen = findBestJar(codePath, updatesDirectory);
                invoke(mainClass, args, bestJarSeen);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void invoke(Class mainClass, String[] args, Path bestJarSeen) throws Exception {
        log.info("Loading {}", bestJarSeen);
        URL url = bestJarSeen.toUri().toURL();
        URLClassLoader classLoader = new URLClassLoader(new URL[] { url }, Bootstrap.class.getClassLoader().getParent());
        Class<?> newClass = Class.forName(mainClass.getCanonicalName(), true, classLoader);
        runBlockingMain(args, newClass);
    }

    private static void runBlockingMain(String[] args, Class<?> newClass) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        // We can't cast to EntryPoint here because it's loaded in a parallel classloader heirarchy.
        Method blockingMain = newClass.getMethod("blockingMain", String[].class);
        blockingMain.invoke(null, new Object[] { args } );
    }

    private static Path findCodePath(Class mainClass) {
        try {
            return Paths.get(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);   // Should never happen.
        }
    }

    private static Path findBestJar(Path origJarPath, Path updatesDirectory) throws IOException {
        if (!Files.isDirectory(updatesDirectory))
            throw new IllegalArgumentException("Not a directory: " + updatesDirectory);
        int bestUpdateSeen = -1;
        Path bestJarSeen = null;
        for (Path path : listDir(updatesDirectory)) {
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

    private static List<Path> listDir(Path dir) throws IOException {
        List<Path> contents = new LinkedList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.forEach(contents::add);
        }
        return contents;
    }
}
