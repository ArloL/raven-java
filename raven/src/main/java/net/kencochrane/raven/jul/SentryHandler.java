package net.kencochrane.raven.jul;

import net.kencochrane.raven.Raven;
import net.kencochrane.raven.RavenFactory;
import net.kencochrane.raven.dsn.Dsn;
import net.kencochrane.raven.dsn.InvalidDsnException;
import net.kencochrane.raven.event.Event;
import net.kencochrane.raven.event.EventBuilder;
import net.kencochrane.raven.event.interfaces.ExceptionInterface;
import net.kencochrane.raven.event.interfaces.MessageInterface;
import net.kencochrane.raven.event.interfaces.StackTraceInterface;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.*;

/**
 * Logging handler in charge of sending the java.util.logging records to a Sentry server.
 */
public class SentryHandler extends Handler {
    /**
     * Name of the {@link Event#extra} property containing the Thread id.
     */
    public static final String THREAD_ID = "Raven-Threadid";
    /**
     * Current instance of {@link Raven}.
     *
     * @see #initRaven()
     */
    protected Raven raven;
    /**
     * DSN property of the appender.
     * <p>
     * Might be null in which case the DSN should be detected automatically.
     * </p>
     */
    protected String dsn;
    /**
     * Name of the {@link RavenFactory} being used.
     * <p>
     * Might be null in which case the factory should be defined automatically.
     * </p>
     */
    protected String ravenFactory;
    private final boolean propagateClose;

    /**
     * Creates an instance of SentryHandler.
     */
    public SentryHandler() {
        propagateClose = true;
        retrieveProperties();
    }

    /**
     * Creates an instance of SentryHandler.
     *
     * @param raven instance of Raven to use with this appender.
     */
    public SentryHandler(Raven raven) {
        this(raven, false);
    }

    /**
     * Creates an instance of SentryHandler.
     *
     * @param raven          instance of Raven to use with this appender.
     * @param propagateClose true if the {@link net.kencochrane.raven.connection.Connection#close()} should be called
     *                       when the appender is closed.
     */
    public SentryHandler(Raven raven, boolean propagateClose) {
        this.raven = raven;
        this.propagateClose = propagateClose;
    }

    /**
     * Transforms a {@link Level} into an {@link Event.Level}.
     *
     * @param level original level as defined in JUL.
     * @return log level used within raven.
     */
    protected static Event.Level getLevel(Level level) {
        if (level.intValue() >= Level.SEVERE.intValue())
            return Event.Level.ERROR;
        else if (level.intValue() >= Level.WARNING.intValue())
            return Event.Level.WARNING;
        else if (level.intValue() >= Level.INFO.intValue())
            return Event.Level.INFO;
        else if (level.intValue() >= Level.ALL.intValue())
            return Event.Level.DEBUG;
        else return null;
    }

    /**
     * Extracts message parameters into a List of Strings.
     * <p>
     * null parameters are kept as null.
     * </p>
     *
     * @param parameters parameters provided to the logging system.
     * @return the parameters formatted as Strings in a List.
     */
    protected static List<String> formatMessageParameters(Object[] parameters) {
        List<String> formattedParameters = new ArrayList<String>(parameters.length);
        for (Object parameter : parameters)
            formattedParameters.add((parameter != null) ? parameter.toString() : null);
        return formattedParameters;
    }

    /**
     * Retrieves the properties of the logger.
     */
    protected void retrieveProperties() {
        LogManager manager = LogManager.getLogManager();
        dsn = manager.getProperty(SentryHandler.class.getName() + ".dsn");
        ravenFactory = manager.getProperty(SentryHandler.class.getName() + ".ravenFactory");
    }

    @Override
    public void publish(LogRecord record) {
        // Do not log the event if the current thread is managed by raven
        if (!isLoggable(record) || Raven.RAVEN_THREAD.get())
            return;

        try {
            Raven.RAVEN_THREAD.set(true);
            if (raven == null)
                initRaven();
            Event event = buildEvent(record);
            raven.sendEvent(event);
        } catch (Exception e) {
            reportError("An exception occurred while creating a new event in Raven", e, ErrorManager.WRITE_FAILURE);
        } finally {
            Raven.RAVEN_THREAD.remove();
        }
    }

    /**
     * Initialises the Raven instance.
     */
    protected void initRaven() {
        try {
            if (dsn == null)
                dsn = Dsn.dsnLookup();

            raven = RavenFactory.ravenInstance(new Dsn(dsn), ravenFactory);
        } catch (InvalidDsnException e) {
            reportError("An exception occurred during the retrieval of the DSN for Raven",
                    e, ErrorManager.OPEN_FAILURE);
        } catch (Exception e) {
            reportError("An exception occurred during the creation of a Raven instance", e, ErrorManager.OPEN_FAILURE);
        }
    }

    /**
     * Builds an Event based on the log record.
     *
     * @param record Log generated.
     * @return Event containing details provided by the logging system.
     */
    protected Event buildEvent(LogRecord record) {
        EventBuilder eventBuilder = new EventBuilder()
                .setLevel(getLevel(record.getLevel()))
                .setTimestamp(new Date(record.getMillis()))
                .setLogger(record.getLoggerName());

        String message = record.getMessage();
        if (record.getResourceBundle() != null && record.getResourceBundle().containsKey(record.getMessage())) {
            message = record.getResourceBundle().getString(record.getMessage());
        }
        if (record.getParameters() != null) {
            List<String> parameters = formatMessageParameters(record.getParameters());
            eventBuilder.addSentryInterface(new MessageInterface(message, parameters));
            message = MessageFormat.format(message, record.getParameters());
        }
        eventBuilder.setMessage(message);

        if (record.getThrown() != null) {
            Throwable throwable = record.getThrown();
            eventBuilder.addSentryInterface(new ExceptionInterface(throwable))
                    .addSentryInterface(new StackTraceInterface(throwable));

            if (throwable.getCause() != null && throwable.getCause() != throwable)
                // As the checksum is based on the stacktrace and the stacktrace contains the message of the parent
                // exceptions, manually generate a checksum to allow groups to work properly.
                // No need to do that if exceptions aren't chained.
                eventBuilder.generateChecksum(buildStackTrace(throwable));
        }

        if (record.getSourceClassName() != null && record.getSourceMethodName() != null) {
            StackTraceElement fakeFrame = new StackTraceElement(record.getSourceClassName(),
                    record.getSourceMethodName(), null, -1);
            eventBuilder.setCulprit(fakeFrame);
        } else {
            eventBuilder.setCulprit(record.getLoggerName());
        }

        eventBuilder.addExtra(THREAD_ID, record.getThreadID());

        raven.runBuilderHelpers(eventBuilder);
        return eventBuilder.build();
    }

    /**
     * Builds a String version of the stacktrace including the stacktrace of the causes.
     *
     * @param e exception from which the stacktrace should be extracted.
     * @return a String version of the stacktrace.
     */
    protected String buildStackTrace(Throwable e) {
        StringBuilder sb = new StringBuilder();
        while (e != null) {
            for (StackTraceElement stackTraceElement : e.getStackTrace()) {
                sb.append(stackTraceElement.getClassName())
                        .append(stackTraceElement.getMethodName())
                        .append(stackTraceElement.getFileName())
                        .append(stackTraceElement.getLineNumber())
                        .append('\n');
            }
            e = e.getCause();
        }
        return sb.toString();
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
        try {
            if (propagateClose && raven != null)
                raven.getConnection().close();
        } catch (IOException e) {
            reportError("An exception occurred while closing the Raven connection", e, ErrorManager.CLOSE_FAILURE);
        }
    }
}
