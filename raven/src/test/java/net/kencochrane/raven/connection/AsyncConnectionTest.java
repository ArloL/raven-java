package net.kencochrane.raven.connection;

import net.kencochrane.raven.event.Event;
import net.kencochrane.raven.event.EventBuilder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class AsyncConnectionTest {
    private AsyncConnection asyncConnection;
    @Mock
    private Connection mockConnection;
    @Mock
    private ExecutorService mockExecutorService;

    @Before
    public void setUp() throws Exception {
        asyncConnection = new AsyncConnection(mockConnection);
        asyncConnection.setExecutorService(mockExecutorService);
    }

    @Test
    public void testCloseOperation() throws Exception {
        asyncConnection.close();

        verify(mockConnection).close();
        verify(mockExecutorService).awaitTermination(any(Long.class), any(TimeUnit.class));
    }

    @Test
    public void testSendEventQueued() throws Exception {
        Event event = new EventBuilder().build();

        asyncConnection.send(event);

        verify(mockExecutorService).execute(any(Runnable.class));
    }

    @Test
    public void testQueuedEventExecuted() throws Exception {
        Event event = new EventBuilder().build();
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ((Runnable) invocation.getArguments()[0]).run();
                return null;
            }
        }).when(mockExecutorService).execute(any(Runnable.class));

        asyncConnection.send(event);

        verify(mockConnection).send(event);
    }
}
