package net.kencochrane.raven.log4j;

import net.kencochrane.raven.Raven;
import net.kencochrane.raven.RavenFactory;
import net.kencochrane.raven.dsn.Dsn;
import net.kencochrane.raven.event.Event;
import net.kencochrane.raven.event.EventBuilder;
import net.kencochrane.raven.event.interfaces.ExceptionInterface;
import net.kencochrane.raven.event.interfaces.StackTraceInterface;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LocationInfo;
import org.apache.log4j.spi.LoggingEvent;

import java.io.IOException;
import java.util.Date;
import java.util.Map;

/**
 * Appender for log4j in charge of sending the logged events to a Sentry server.
 */
public class SentryAppender extends AppenderSkeleton {
    private static final String LOG4J_NDC = "log4J-NDC";
    private final boolean propagateClose;
    private Raven raven;
    private String dsn;
    private String ravenFactory;
    private boolean guard;

    public SentryAppender() {
        this.propagateClose = true;
    }

    public SentryAppender(Raven raven) {
        this(raven, false);
    }

    public SentryAppender(Raven raven, boolean propagateClose) {
        this.raven = raven;
        this.propagateClose = propagateClose;
    }

    private static Event.Level formatLevel(Level level) {
        if (level.isGreaterOrEqual(Level.FATAL)) {
            return Event.Level.FATAL;
        } else if (level.isGreaterOrEqual(Level.ERROR)) {
            return Event.Level.ERROR;
        } else if (level.isGreaterOrEqual(Level.WARN)) {
            return Event.Level.WARNING;
        } else if (level.isGreaterOrEqual(Level.INFO)) {
            return Event.Level.INFO;
        } else if (level.isGreaterOrEqual(Level.ALL)) {
            return Event.Level.DEBUG;
        } else return null;
    }

    private static StackTraceElement asStackTraceElement(LocationInfo location) {
        String fileName = (LocationInfo.NA.equals(location.getFileName())) ? null : location.getFileName();
        int line = (LocationInfo.NA.equals(location.getLineNumber())) ? -1 : Integer.parseInt(location.getLineNumber());
        return new StackTraceElement(location.getClassName(), location.getMethodName(), fileName, line);
    }

    @Override
    public void activateOptions() {
        try {
            if (raven == null) {
                if (dsn == null)
                    dsn = Dsn.dsnLookup();

                raven = RavenFactory.ravenInstance(new Dsn(dsn), ravenFactory);
            }
            super.activateOptions();
        } catch (Exception e) {
            getErrorHandler().error("An exception occurred during the creation of a raven instance", e,
                    ErrorCode.FILE_OPEN_FAILURE);
        }
    }

    @Override
    protected synchronized void append(LoggingEvent loggingEvent) {
        // Do not log the event if the current thread has been spawned by raven or if the event has been created during
        // the logging of an other event.
        if (Raven.RAVEN_THREAD.get() || guard)
            return;

        try {
            guard = true;
            Event event = buildEvent(loggingEvent);
            raven.sendEvent(event);
        } finally {
            guard = false;
        }
    }

    private Event buildEvent(LoggingEvent loggingEvent) {
        EventBuilder eventBuilder = new EventBuilder()
                .setTimestamp(new Date(loggingEvent.getTimeStamp()))
                .setMessage(loggingEvent.getRenderedMessage())
                .setLogger(loggingEvent.getLoggerName())
                .setLevel(formatLevel(loggingEvent.getLevel()));

        if (loggingEvent.getThrowableInformation() != null) {
            Throwable throwable = loggingEvent.getThrowableInformation().getThrowable();
            eventBuilder.addSentryInterface(new ExceptionInterface(throwable))
                    .addSentryInterface(new StackTraceInterface(throwable));

            if (throwable.getCause() != null && throwable.getCause() != throwable)
                // As the checksum is based on the stacktrace and the stacktrace contains the message of the parent
                // exceptions, manually generate a checksum to allow groups to work properly.
                // No need to do that if exceptions aren't chained.
                eventBuilder.generateChecksum(buildStackTrace(throwable));
        } else if (loggingEvent.getLocationInformation().fullInfo != null) {
            // When it's a message try to rely on the position of the log (the same message can be logged from
            // different places, or a same place can log a message in different ways).
            eventBuilder.generateChecksum(loggingEvent.getLocationInformation().fullInfo);
        }

        // Set culprit
        if (loggingEvent.getLocationInformation().fullInfo != null) {
            eventBuilder.setCulprit(asStackTraceElement(loggingEvent.getLocationInformation()));
        } else {
            eventBuilder.setCulprit(loggingEvent.getLoggerName());
        }

        if (loggingEvent.getNDC() != null)
            eventBuilder.addExtra(LOG4J_NDC, loggingEvent.getNDC());

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) loggingEvent.getProperties();
        for (Map.Entry<String, Object> mdcEntry : properties.entrySet())
            eventBuilder.addExtra(mdcEntry.getKey(), mdcEntry.getValue());

        raven.runBuilderHelpers(eventBuilder);
        return eventBuilder.build();
    }

    private String buildStackTrace(Throwable e) {
        StringBuilder sb = new StringBuilder();
        do {
            for (StackTraceElement stackTraceElement : e.getStackTrace()) {
                sb.append(stackTraceElement.getClassName())
                        .append(stackTraceElement.getMethodName())
                        .append(stackTraceElement.getFileName())
                        .append(stackTraceElement.getLineNumber())
                        .append('\n');
            }
        } while (e.getCause() != e && e.getCause() != null);

        return sb.toString();
    }

    public void setDsn(String dsn) {
        this.dsn = dsn;
    }

    public void setRavenFactory(String ravenFactory) {
        this.ravenFactory = ravenFactory;
    }

    @Override
    public void close() {
        try {
            if (propagateClose)
                raven.getConnection().close();
        } catch (IOException e) {
            getErrorHandler().error("An exception occurred while closing the raven connection", e,
                    ErrorCode.CLOSE_FAILURE);
        }
    }

    @Override
    public boolean requiresLayout() {
        return false;
    }
}
