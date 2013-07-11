package net.kencochrane.raven.log4j;

import net.kencochrane.raven.AbstractLoggerTest;
import net.kencochrane.raven.event.Event;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.apache.log4j.NDC;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.Mockito.*;

public class SentryAppenderTest extends AbstractLoggerTest {
    private Logger logger;

    @Before
    public void setUp() throws Exception {
        logger = Logger.getLogger(SentryAppenderTest.class);
        logger.setLevel(Level.ALL);
        logger.setAdditivity(false);
        logger.addAppender(new SentryAppender(getMockRaven()));
    }

    @Override
    public void logAnyLevel(String message) {
        logger.info(message);
    }

    @Override
    public void logAnyLevel(String message, Throwable exception) {
        logger.error(message, exception);
    }

    @Override
    public void logAnyLevel(String message, List<String> parameters) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getCurrentLoggerName() {
        return logger.getName();
    }

    @Override
    public String getUnformattedMessage() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Test
    public void testLogLevelConversions() throws Exception {
        assertLevelConverted(Event.Level.DEBUG, Level.TRACE);
        assertLevelConverted(Event.Level.DEBUG, Level.DEBUG);
        assertLevelConverted(Event.Level.INFO, Level.INFO);
        assertLevelConverted(Event.Level.WARNING, Level.WARN);
        assertLevelConverted(Event.Level.ERROR, Level.ERROR);
        assertLevelConverted(Event.Level.FATAL, Level.FATAL);
    }

    private void assertLevelConverted(Event.Level expectedLevel, Level level) {
        logger.log(level, null);
        assertLogLevel(expectedLevel);
    }

    @Override
    public void testLogParametrisedMessage() throws Exception {
        // Parametrised messages aren't supported
    }

    @Test
    public void testThreadNameAddedToExtra() {
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        logger.info("testMessage");

        verify(mockRaven).sendEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getExtra(),
                Matchers.<String, Object>hasEntry(SentryAppender.THREAD_NAME, Thread.currentThread().getName()));
    }

    @Test
    public void testMdcAddedToExtra() {
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        String extraKey = UUID.randomUUID().toString();
        Object extraValue = mock(Object.class);

        MDC.put(extraKey, extraValue);

        logger.info("testMessage");

        verify(mockRaven).sendEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getExtra(), hasEntry(extraKey, extraValue));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testNdcAddedToExtra() {
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        String extra = UUID.randomUUID().toString();
        String extra2 = UUID.randomUUID().toString();

        NDC.push(extra);
        logger.info("testMessage");

        verify(mockRaven).sendEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getExtra(), hasKey(SentryAppender.LOG4J_NDC));
        assertThat(eventCaptor.getValue().getExtra().get(SentryAppender.LOG4J_NDC), Matchers.<Object>is(extra));

        reset(mockRaven);
        NDC.push(extra2);
        logger.info("testMessage");

        verify(mockRaven).sendEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getExtra(), hasKey(SentryAppender.LOG4J_NDC));
        assertThat(eventCaptor.getValue().getExtra().get(SentryAppender.LOG4J_NDC),
                Matchers.<Object>is(extra + " " + extra2));
    }
}
