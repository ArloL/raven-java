package net.kencochrane.raven.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Appender for logback in charge of sending the logged events to a Sentry server.
 */
public class SentryAppender extends AppenderBase<ILoggingEvent> {
    /**
     * Name of the {@link Event#extra} property containing Maker details.
     */
    public static final String LOGBACK_MARKER = "logback-Marker";
    /**
     * Name of the {@link Event#extra} property containing the Thread name.
     */
    public static final String THREAD_NAME = "Raven-Threadname";
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
     * Creates an instance of SentryAppender.
     */
    public SentryAppender() {
        propagateClose = true;
    }

    /**
     * Creates an instance of SentryAppender.
     *
     * @param raven instance of Raven to use with this appender.
     */
    public SentryAppender(Raven raven) {
        this(raven, false);
    }

    /**
     * Creates an instance of SentryAppender.
     *
     * @param raven          instance of Raven to use with this appender.
     * @param propagateClose true if the {@link net.kencochrane.raven.connection.Connection#close()} should be called
     *                       when the appender is closed.
     */
    public SentryAppender(Raven raven, boolean propagateClose) {
        this.raven = raven;
        this.propagateClose = propagateClose;
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
        List<String> arguments = new ArrayList<String>(parameters.length);
        for (Object argument : parameters) {
            arguments.add((argument != null) ? argument.toString() : null);
        }
        return arguments;
    }

    /**
     * Transforms a {@link Level} into an {@link Event.Level}.
     *
     * @param level original level as defined in logback.
     * @return log level used within raven.
     */
    protected static Event.Level formatLevel(Level level) {
        if (level.isGreaterOrEqual(Level.ERROR)) {
            return Event.Level.ERROR;
        } else if (level.isGreaterOrEqual(Level.WARN)) {
            return Event.Level.WARNING;
        } else if (level.isGreaterOrEqual(Level.INFO)) {
            return Event.Level.INFO;
        } else if (level.isGreaterOrEqual(Level.ALL)) {
            return Event.Level.DEBUG;
        } else return null;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The raven instance is started in this method instead of {@link #start()} in order to avoid substitute loggers
     * being generated during the instantiation of {@link Raven}.<br />
     * More on <a href="http://www.slf4j.org/codes.html#substituteLogger">www.slf4j.org/codes.html#substituteLogger</a>
     * </p>
     */
    @Override
    protected void append(ILoggingEvent iLoggingEvent) {
        // Do not log the event if the current thread has been spawned by raven
        if (Raven.RAVEN_THREAD.get())
            return;

        if (raven == null)
            initRaven();

        try {
            Event event = buildEvent(iLoggingEvent);
            raven.sendEvent(event);
        } catch (Exception e) {
            addError("An exception occurred while creating a new event in Raven", e);
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
            addError("An exception occurred during the retrieval of the DSN for Raven", e);
        } catch (Exception e) {
            addError("An exception occurred during the creation of a Raven instance", e);
        }
    }

    /**
     * Builds an Event based on the logging event.
     *
     * @param iLoggingEvent Log generated.
     * @return Event containing details provided by the logging system.
     */
    protected Event buildEvent(ILoggingEvent iLoggingEvent) {
        EventBuilder eventBuilder = new EventBuilder()
                .setTimestamp(new Date(iLoggingEvent.getTimeStamp()))
                .setMessage(iLoggingEvent.getFormattedMessage())
                .setLogger(iLoggingEvent.getLoggerName())
                .setLevel(formatLevel(iLoggingEvent.getLevel()))
                .addExtra(THREAD_NAME, iLoggingEvent.getThreadName());

        if (iLoggingEvent.getArgumentArray() != null) {
            eventBuilder.addSentryInterface(new MessageInterface(iLoggingEvent.getMessage(),
                    formatMessageParameters(iLoggingEvent.getArgumentArray())));
        }

        if (iLoggingEvent.getThrowableProxy() != null) {
            Throwable throwable = ((ThrowableProxy) iLoggingEvent.getThrowableProxy()).getThrowable();
            eventBuilder.addSentryInterface(new ExceptionInterface(throwable))
                    .addSentryInterface(new StackTraceInterface(throwable));

            if (throwable.getCause() != null && throwable.getCause() != throwable)
                // As the checksum is based on the stacktrace and the stacktrace contains the message of the parent
                // exceptions, manually generate a checksum to allow groups to work properly.
                // No need to do that if exceptions aren't chained.
                eventBuilder.generateChecksum(buildStackTrace(throwable));
        } else if (iLoggingEvent.getCallerData().length > 0) {
            Throwable throwable = new Throwable();
            throwable.setStackTrace(iLoggingEvent.getCallerData());
            eventBuilder.addSentryInterface(new StackTraceInterface(throwable));
        }

        if (iLoggingEvent.getCallerData().length > 0) {
            eventBuilder.setCulprit(iLoggingEvent.getCallerData()[0]);
        } else {
            eventBuilder.setCulprit(iLoggingEvent.getLoggerName());
        }

        for (Map.Entry<String, String> mdcEntry : iLoggingEvent.getMDCPropertyMap().entrySet()) {
            eventBuilder.addExtra(mdcEntry.getKey(), mdcEntry.getValue());
        }

        if (iLoggingEvent.getMarker() != null)
            eventBuilder.addTag(LOGBACK_MARKER, iLoggingEvent.getMarker().getName());

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

    public void setDsn(String dsn) {
        this.dsn = dsn;
    }

    public void setRavenFactory(String ravenFactory) {
        this.ravenFactory = ravenFactory;
    }

    @Override
    public void stop() {
        super.stop();

        try {
            if (propagateClose && raven != null)
                raven.getConnection().close();
        } catch (IOException e) {
            addError("An exception occurred while closing the Raven connection", e);
        }
    }
}
