import com.vinumeris.updatefx.AppDirectory;
import com.vinumeris.updatefx.Bootstrap;

import java.io.IOException;

public class ExampleApp {
    public static int VERSION = 2;

    public static void main(String[] args) throws IOException {
        AppDirectory.initAppDir("UpdateFX Example App");
        Bootstrap.bootstrap(ExampleApp.class, AppDirectory.dir(), args);
    }

    public static void blockingMain(String[] args) {
        // We can return from here to get restarted to a newer version, but normally we don't want that.
        System.out.println("Hello World! This is version " + VERSION);
        Runtime.getRuntime().exit(0);
    }
}
