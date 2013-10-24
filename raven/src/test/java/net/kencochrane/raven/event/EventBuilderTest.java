package net.kencochrane.raven.event;

import mockit.Delegate;
import mockit.Injectable;
import mockit.Mocked;
import mockit.NonStrictExpectations;
import net.kencochrane.raven.event.interfaces.SentryInterface;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.InetAddress;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static mockit.Deencapsulation.newInstance;
import static mockit.Deencapsulation.setField;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class EventBuilderTest {
    private EventBuilder eventBuilder;
    @Mocked
    private InetAddress inetAddress;

    @BeforeMethod
    public void setUp() throws Exception {
        eventBuilder = new EventBuilder();
        new NonStrictExpectations() {{
            InetAddress.getLocalHost();
            result = inetAddress;
        }};
        //Create a temporary cache with a timeout of 0
        setField(EventBuilder.class, "HOSTNAME_CACHE",
                newInstance(EventBuilder.class.getName() + "$HostnameCache", 0l));
    }

    @Test
    public void testMandatoryValuesAutomaticallySet() throws Exception {
        final String expectedWorkingHostname = UUID.randomUUID().toString();
        new NonStrictExpectations() {{
            inetAddress.getCanonicalHostName();
            result = expectedWorkingHostname;
        }};
        Event event = eventBuilder.build();

        assertThat(event.getId(), is(notNullValue()));
        assertThat(event.getTimestamp(), is(notNullValue()));
        assertThat(event.getPlatform(), is(EventBuilder.DEFAULT_PLATFORM));
        assertThat(event.getServerName(), is(expectedWorkingHostname));
    }

    @Test
    public void slowCallToGetCanonicalHostNameIsCaught() throws Exception {
        new NonStrictExpectations() {{
            inetAddress.getCanonicalHostName();
            result = new Delegate() {
                public String getCanonicalHostName() throws Exception {
                    synchronized (EventBuilderTest.this) {
                        EventBuilderTest.this.wait();
                    }
                    return "";
                }
            };
        }};

        Event event = eventBuilder.build();
        synchronized (EventBuilderTest.this) {
            EventBuilderTest.this.notify();
        }

        assertThat(event.getServerName(), is(EventBuilder.DEFAULT_HOSTNAME));

    }

    @Test
    public void testMandatoryValuesNotOverwritten() throws Exception {
        UUID uuid = UUID.randomUUID();
        Date timestamp = new Date();
        String platform = UUID.randomUUID().toString();
        String serverName = UUID.randomUUID().toString();

        Event event = new EventBuilder(uuid)
                .setTimestamp(timestamp)
                .setPlatform(platform)
                .setServerName(serverName)
                .build();

        assertThat(event.getId(), is(uuid));
        assertThat(event.getTimestamp(), is(timestamp));
        assertThat(event.getPlatform(), is(platform));
        assertThat(event.getServerName(), is(serverName));
    }

    @Test
    public void testEventParameters() throws Exception {
        String culprit = UUID.randomUUID().toString();
        String checksum = UUID.randomUUID().toString();
        Event.Level level = Event.Level.INFO;
        String logger = UUID.randomUUID().toString();
        String message = UUID.randomUUID().toString();

        Event event = eventBuilder.setCulprit(culprit)
                .setChecksum(checksum)
                .setLevel(level)
                .setLogger(logger)
                .setMessage(message)
                .build();

        assertThat(event.getCulprit(), is(culprit));
        assertThat(event.getChecksum(), is(checksum));
        assertThat(event.getLevel(), is(level));
        assertThat(event.getLogger(), is(logger));
        assertThat(event.getMessage(), is(message));
    }

    @Test
    public void testUseStackFrameAsCulprit() {
        StackTraceElement frame1 = new StackTraceElement("class", "method", "file", 1);
        StackTraceElement frame2 = new StackTraceElement("class", "method", "file", -1);
        StackTraceElement frame3 = new StackTraceElement("class", "method", null, 1);

        String culprit1 = new EventBuilder().setCulprit(frame1).build().getCulprit();
        String culprit2 = new EventBuilder().setCulprit(frame2).build().getCulprit();
        String culprit3 = new EventBuilder().setCulprit(frame3).build().getCulprit();

        assertThat(culprit1, is("class.method(file:1)"));
        assertThat(culprit2, is("class.method(file)"));
        assertThat(culprit3, is("class.method"));
    }

    @Test
    public void testChecksumGeneration() throws Exception {
        String cont = UUID.randomUUID().toString();
        Event noChecksumEvent = new EventBuilder().build();
        Event firstChecksumEvent = new EventBuilder().generateChecksum(cont).build();
        Event secondChecksumEvent = new EventBuilder().generateChecksum(cont).build();
        Event differentChecksumEvent = new EventBuilder().generateChecksum(UUID.randomUUID().toString()).build();

        assertThat(noChecksumEvent.getChecksum(), is(nullValue()));
        assertThat(firstChecksumEvent.getChecksum(), is(notNullValue()));
        assertThat(differentChecksumEvent.getChecksum(), is(notNullValue()));
        assertThat(firstChecksumEvent.getChecksum(), is(not(differentChecksumEvent.getChecksum())));
        assertThat(firstChecksumEvent.getChecksum(), is(secondChecksumEvent.getChecksum()));
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testTagsAreImmutable() throws Exception {
        String tagKey = UUID.randomUUID().toString();
        String tagValue = UUID.randomUUID().toString();

        Map<String, String> tags = eventBuilder.addTag(tagKey, tagValue).build().getTags();

        assertThat(tags.size(), is(1));
        assertThat(tags.get(tagKey), is(tagValue));

        tags.put(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testExtrasAreImmutable() throws Exception {
        final String extraKey = UUID.randomUUID().toString();
        final Object extraValue = new Object();

        Map<String, Object> extra = eventBuilder.addExtra(extraKey, extraValue).build().getExtra();

        assertThat(extra.size(), is(1));
        assertThat(extra.get(extraKey), is(extraValue));

        extra.put(UUID.randomUUID().toString(), new Object());
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testSentryInterfacesAreImmutable(@Injectable final SentryInterface sentryInterface) throws Exception {
        final String interfaceName = UUID.randomUUID().toString();
        new NonStrictExpectations() {{
            sentryInterface.getInterfaceName();
            result = interfaceName;
        }};

        Map<String, SentryInterface> sentryInterfaces = eventBuilder
                .addSentryInterface(sentryInterface)
                .build()
                .getSentryInterfaces();

        assertThat(sentryInterfaces.size(), is(1));
        assertThat(sentryInterfaces.get(sentryInterface.getInterfaceName()), is(sentryInterface));

        sentryInterfaces.put(UUID.randomUUID().toString(), null);
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testBuildCanBeCalledOnlyOnce() throws Exception {
        eventBuilder.build();
        eventBuilder.build();
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testNoUuidFails() throws Exception {
        new EventBuilder(null);
    }
}
