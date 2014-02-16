package net.kencochrane.raven.log4j2;

import mockit.Injectable;
import mockit.Verifications;
import net.kencochrane.raven.Raven;
import net.kencochrane.raven.event.Event;
import net.kencochrane.raven.event.EventBuilder;
import net.kencochrane.raven.event.interfaces.ExceptionInterface;
import net.kencochrane.raven.event.interfaces.MessageInterface;
import net.kencochrane.raven.event.interfaces.SentryException;
import net.kencochrane.raven.event.interfaces.StackTraceInterface;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.FormattedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.DefaultThreadContextStack;
import org.hamcrest.Matchers;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class SentryAppenderEventBuildingTest {
    private SentryAppender sentryAppender;
    private MockUpErrorHandler mockUpErrorHandler;
    @Injectable
    private Raven mockRaven = null;

    @BeforeMethod
    public void setUp() throws Exception {
        sentryAppender = new SentryAppender(mockRaven);
        mockUpErrorHandler = new MockUpErrorHandler();
        sentryAppender.setHandler(mockUpErrorHandler.getMockInstance());
    }

    private void assertNoErrorsInErrorHandler() throws Exception {
        assertThat(mockUpErrorHandler.getErrorCount(), is(0));
    }

    @Test
    public void testSimpleMessageLogging() throws Exception {
        final String loggerName = UUID.randomUUID().toString();
        final String message = UUID.randomUUID().toString();
        final String threadName = UUID.randomUUID().toString();
        final Date date = new Date(1373883196416L);

        sentryAppender.append(new Log4jLogEvent(loggerName, null, null, Level.INFO, new SimpleMessage(message),
                null, null, null, threadName, null, date.getTime()));

        new Verifications() {{
            Event event;
            mockRaven.runBuilderHelpers((EventBuilder) any);
            mockRaven.sendEvent(event = withCapture());
            assertThat(event.getMessage(), is(message));
            assertThat(event.getLogger(), is(loggerName));
            assertThat(event.getExtra(), Matchers.<String, Object>hasEntry(SentryAppender.THREAD_NAME, threadName));
            assertThat(event.getTimestamp(), is(date));
        }};
        assertNoErrorsInErrorHandler();
    }

    @DataProvider(name = "levels")
    private Object[][] levelConversions() {
        return new Object[][]{
                {Event.Level.DEBUG, Level.TRACE},
                {Event.Level.DEBUG, Level.DEBUG},
                {Event.Level.INFO, Level.INFO},
                {Event.Level.WARNING, Level.WARN},
                {Event.Level.ERROR, Level.ERROR},
                {Event.Level.FATAL, Level.FATAL}};
    }

    @Test(dataProvider = "levels")
    public void testLevelConversion(final Event.Level expectedLevel, Level level) throws Exception {
        sentryAppender.append(new Log4jLogEvent(null, null, null, level, new SimpleMessage(""), null));

        new Verifications() {{
            Event event;
            mockRaven.sendEvent(event = withCapture());
            assertThat(event.getLevel(), is(expectedLevel));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testExceptionLogging() throws Exception {
        final Exception exception = new Exception(UUID.randomUUID().toString());

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.ERROR, new SimpleMessage(""), exception));

        new Verifications() {{
            Event event;
            mockRaven.sendEvent(event = withCapture());
            ExceptionInterface exceptionInterface = (ExceptionInterface) event.getSentryInterfaces()
                    .get(ExceptionInterface.EXCEPTION_INTERFACE);
            SentryException sentryException = exceptionInterface.getExceptions().getFirst();
            assertThat(sentryException.getExceptionMessage(), is(exception.getMessage()));
            assertThat(sentryException.getStackTraceInterface().getStackTrace(), is(exception.getStackTrace()));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testLogParametrisedMessage() throws Exception {
        final String messagePattern = "Formatted message {} {} {}";
        final Object[] parameters = {"first parameter", new Object[0], null};

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO,
                new FormattedMessage(messagePattern, parameters), null));

        new Verifications() {{
            Event event;
            mockRaven.sendEvent(event = withCapture());

            MessageInterface messageInterface = (MessageInterface) event.getSentryInterfaces()
                    .get(MessageInterface.MESSAGE_INTERFACE);
            assertThat(event.getMessage(), is("Formatted message first parameter [] null"));
            assertThat(messageInterface.getMessage(), is(messagePattern));
            assertThat(messageInterface.getParameters(),
                    is(Arrays.asList(parameters[0].toString(), parameters[1].toString(), null)));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testMarkerAddedToTag() throws Exception {
        final String markerName = UUID.randomUUID().toString();

        sentryAppender.append(new Log4jLogEvent(null, MarkerManager.getMarker(markerName), null, Level.INFO,
                new SimpleMessage(""), null));

        new Verifications() {{
            Event event;
            mockRaven.sendEvent(event = withCapture());
            assertThat(event.getTags(), Matchers.<String, Object>hasEntry(SentryAppender.LOG4J_MARKER, markerName));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testMdcAddedToExtra() throws Exception {
        final String extraKey = UUID.randomUUID().toString();
        final String extraValue = UUID.randomUUID().toString();

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO, new SimpleMessage(""), null,
                Collections.singletonMap(extraKey, extraValue), null, null, null, 0));

        new Verifications() {{
            Event event;
            mockRaven.sendEvent(event = withCapture());
            assertThat(event.getExtra(), Matchers.<String, Object>hasEntry(extraKey, extraValue));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testNdcAddedToExtra() throws Exception {
        final ThreadContext.ContextStack contextStack = new DefaultThreadContextStack(true);
        contextStack.push(UUID.randomUUID().toString());
        contextStack.push(UUID.randomUUID().toString());
        contextStack.push(UUID.randomUUID().toString());

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO, new SimpleMessage(""), null, null,
                contextStack, null, null, 0));

        new Verifications() {{
            Event event;
            mockRaven.sendEvent(event = withCapture());
            assertThat((List<String>) event.getExtra().get(SentryAppender.LOG4J_NDC), equalTo(contextStack.asList()));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testSourceUsedAsStacktrace() throws Exception {
        final StackTraceElement location = new StackTraceElement(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 42);

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO, new SimpleMessage(""), null, null, null,
                null, location, 0));

        new Verifications() {{
            Event event;
            mockRaven.sendEvent(event = withCapture());
            StackTraceInterface stackTraceInterface = (StackTraceInterface) event.getSentryInterfaces()
                    .get(StackTraceInterface.STACKTRACE_INTERFACE);
            assertThat(stackTraceInterface.getStackTrace(), arrayWithSize(1));
            assertThat(stackTraceInterface.getStackTrace()[0], is(location));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testCulpritWithSource() throws Exception {
        final StackTraceElement location = new StackTraceElement("a", "b", "c", 42);

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO, new SimpleMessage(""), null, null, null,
                null, location, 0));

        new Verifications() {{
            Event event;
            mockRaven.sendEvent(event = withCapture());
            assertThat(event.getCulprit(), is("a.b(c:42)"));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testCulpritWithoutSource() throws Exception {
        final String loggerName = UUID.randomUUID().toString();

        sentryAppender.append(new Log4jLogEvent(loggerName, null, null, Level.INFO, new SimpleMessage(""), null));

        new Verifications() {{
            Event event;
            mockRaven.sendEvent(event = withCapture());
            assertThat(event.getCulprit(), is(loggerName));
        }};
        assertNoErrorsInErrorHandler();
    }
}
