import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.MessageFormat;
import java.util.Date;
import java.util.logging.*;

/**
 * A Java logging formatter that writes more compact output than the default.
 */
public class BriefLogFormatter extends Formatter {
    private static final MessageFormat messageFormat = new MessageFormat("{3,date,hh:mm:ss} {0} {1}.{2}: {4}\n{5}");

    // OpenJDK made a questionable, backwards incompatible change to the Logger implementation. It internally uses
    // weak references now which means simply fetching the logger and changing its configuration won't work. We must
    // keep a reference to our custom logger around.
    private static Logger logger;

    /** Configures JDK logging to use this class for everything. */
    public static void init() {
        logger = Logger.getLogger("");
        final Handler[] handlers = logger.getHandlers();
        // In regular Java there is always a handler. Avian doesn't install one however.
        if (handlers.length > 0)
            handlers[0].setFormatter(new BriefLogFormatter());
    }

    public static void initVerbose() {
        init();
        logger.setLevel(Level.ALL);
        logger.log(Level.FINE, "test");
    }

    @Override
    public String format(LogRecord logRecord) {
        Object[] arguments = new Object[6];
        arguments[0] = logRecord.getThreadID();
        String fullClassName = logRecord.getSourceClassName();
        int lastDot = fullClassName.lastIndexOf('.');
        String className = fullClassName.substring(lastDot + 1);
        arguments[1] = className;
        arguments[2] = logRecord.getSourceMethodName();
        arguments[3] = new Date(logRecord.getMillis());
        arguments[4] = logRecord.getMessage();
        if (logRecord.getThrown() != null) {
            Writer result = new StringWriter();
            logRecord.getThrown().printStackTrace(new PrintWriter(result));
            arguments[5] = result.toString();
        } else {
            arguments[5] = "";
        }
        return messageFormat.format(arguments);
    }
}
