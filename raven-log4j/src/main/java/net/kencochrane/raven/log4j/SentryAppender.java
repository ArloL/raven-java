package net.kencochrane.raven.log4j;

import net.kencochrane.raven.Dsn;
import net.kencochrane.raven.Raven;
import net.kencochrane.raven.RavenFactory;
import net.kencochrane.raven.event.Event;
import net.kencochrane.raven.event.EventBuilder;
import net.kencochrane.raven.event.interfaces.ExceptionInterface;
import net.kencochrane.raven.event.interfaces.StackTraceInterface;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.spi.LoggingEvent;

import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.Set;

public class SentryAppender extends AppenderSkeleton {
    private static final String LOG4J_NDC = "Log4J-NDC";
    private final boolean propagateClose;
    private Raven raven;
    private String dsn;

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

    @Override
    public void activateOptions() {
        if (dsn == null)
            dsn = Dsn.dsnLookup();

        if (raven == null) {
            //TODO: Add a way to select the factory
            raven = RavenFactory.ravenInstance(new Dsn(dsn));
        }
    }

    @Override
    protected void append(LoggingEvent loggingEvent) {
        EventBuilder eventBuilder = new EventBuilder()
                .setTimestamp(new Date(loggingEvent.getTimeStamp()))
                .setMessage(loggingEvent.getRenderedMessage())
                .setLogger(loggingEvent.getLoggerName())
                .setLevel(formatLevel(loggingEvent.getLevel()))
                .setCulprit(loggingEvent.getLoggerName());

        if (loggingEvent.getThrowableInformation() != null) {
            Throwable throwable = loggingEvent.getThrowableInformation().getThrowable();
            eventBuilder.addSentryInterface(new ExceptionInterface(throwable))
                    .addSentryInterface(new StackTraceInterface(throwable));
            eventBuilder.setCulprit(throwable);
        } else if (loggingEvent.getLocationInformation().fullInfo != null) {
            // When it's a message try to rely on the position of the log (the same message can be logged from
            // different places, or a same place can log a message in different ways).
            eventBuilder.generateChecksum(loggingEvent.getLocationInformation().fullInfo);
            eventBuilder.setCulprit(loggingEvent.getLocationInformation().fullInfo);
        }

        if (loggingEvent.getNDC() != null)
            eventBuilder.addExtra(LOG4J_NDC, loggingEvent.getNDC());

        for (Map.Entry mdcEntry : (Set<Map.Entry>) loggingEvent.getProperties().entrySet())
            eventBuilder.addExtra(mdcEntry.getKey().toString(), mdcEntry.getValue());

        raven.runBuilderHelpers(eventBuilder);

        raven.sendEvent(eventBuilder.build());
    }

    public void setDsn(String dsn) {
        this.dsn = dsn;
    }

    @Override
    public void close() {
        try {
            if (propagateClose)
                raven.getConnection().close();
        } catch (IOException e) {
            //TODO: What to do with that exception?
            e.printStackTrace();
        }
    }

    @Override
    public boolean requiresLayout() {
        return false;
    }
}
