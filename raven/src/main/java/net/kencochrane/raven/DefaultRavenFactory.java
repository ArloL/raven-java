package net.kencochrane.raven;

import net.kencochrane.raven.connection.AsyncConnection;
import net.kencochrane.raven.connection.Connection;
import net.kencochrane.raven.connection.HttpConnection;
import net.kencochrane.raven.connection.UdpConnection;
import net.kencochrane.raven.event.helper.HttpEventBuilderHelper;
import net.kencochrane.raven.event.interfaces.ExceptionInterface;
import net.kencochrane.raven.event.interfaces.HttpInterface;
import net.kencochrane.raven.event.interfaces.MessageInterface;
import net.kencochrane.raven.event.interfaces.StackTraceInterface;
import net.kencochrane.raven.marshaller.Marshaller;
import net.kencochrane.raven.marshaller.json.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DefaultRavenFactory extends RavenFactory {
    /**
     * Protocol setting to disable security checks over an SSL connection.
     */
    public static final String NAIVE_PROTOCOL = "naive";
    /**
     * Option specific to raven-java, allowing to disable the compression of requests to the Sentry Server.
     */
    public static final String NOCOMPRESSION_OPTION = "raven.nocompression";
    /**
     * Option specific to raven-java, allowing to set a timeout (in ms) for a request to the Sentry server.
     */
    public static final String TIMEOUT_OPTION = "raven.timeout";
    /**
     * Option to send events asynchronously.
     */
    public static final String ASYNC_OPTION = "raven.async";
    /**
     * Option for the number of threads assigned for the connection.
     */
    public static final String MAX_THREADS_OPTION = "raven.async.threads";
    /**
     * Option for the priority of threads assigned for the connection.
     */
    public static final String PRIORITY_OPTION = "raven.async.priority";
    /**
     * Option to hide common stackframes with enclosing exceptions.
     */
    public static final String HIDE_COMMON_FRAMES_OPTION = "raven.stacktrace.hidecommon";
    private static final Logger logger = Logger.getLogger(DefaultRavenFactory.class.getCanonicalName());

    @Override
    public Raven createRavenInstance(Dsn dsn) {
        Raven raven = new Raven();
        raven.setConnection(createConnection(dsn));
        try {
            Class.forName("javax.servlet.Servlet", false, this.getClass().getClassLoader());
            //TODO: Is it enough? Shouldn't it look for Servlet >= 3.0 ?
            raven.addBuilderHelper(new HttpEventBuilderHelper());
        } catch (Exception e) {
            logger.fine("It seems that the current environment doesn't provide access to servlets.");
        }
        return raven;
    }

    protected Connection createConnection(Dsn dsn) {
        String protocol = dsn.getProtocol();
        Connection connection;

        if (protocol.equalsIgnoreCase("http") || protocol.equalsIgnoreCase("https")) {
            logger.log(Level.INFO, "Using an HTTP connection to Sentry.");
            connection = createHttpConnection(dsn);
        } else if (protocol.equalsIgnoreCase("udp")) {
            logger.log(Level.INFO, "Using an UDP connection to Sentry.");
            connection = createUdpConnection(dsn);
        } else {
            throw new IllegalStateException("Couldn't create a connection for the protocol '" + protocol + "'");
        }

        if (dsn.getOptions().containsKey(ASYNC_OPTION)) {
            connection = createAsyncConnection(dsn, connection);
        }

        return connection;
    }

    protected Connection createAsyncConnection(Dsn dsn, Connection connection) {
        AsyncConnection asyncConnection = new AsyncConnection(connection, true);

        int maxThreads;
        if (dsn.getOptions().containsKey(MAX_THREADS_OPTION)) {
            maxThreads = Integer.parseInt(dsn.getOptions().get(MAX_THREADS_OPTION));
        } else {
            maxThreads = Runtime.getRuntime().availableProcessors();
        }

        int priority;
        if (dsn.getOptions().containsKey(PRIORITY_OPTION)) {
            priority = Integer.parseInt(dsn.getOptions().get(PRIORITY_OPTION));
        } else {
            priority = Thread.MIN_PRIORITY;
        }

        asyncConnection.setExecutorService(Executors.newFixedThreadPool(maxThreads, new DaemonThreadFactory(priority)));

        return asyncConnection;
    }

    protected Connection createHttpConnection(Dsn dsn) {
        HttpConnection httpConnection = new HttpConnection(HttpConnection.getSentryApiUrl(dsn),
                dsn.getPublicKey(), dsn.getSecretKey());
        httpConnection.setMarshaller(createMarshaller(dsn));

        // Set the naive mode
        httpConnection.setBypassSecurity(dsn.getProtocolSettings().contains(NAIVE_PROTOCOL));
        // Set the HTTP timeout
        if (dsn.getOptions().containsKey(TIMEOUT_OPTION))
            httpConnection.setTimeout(Integer.parseInt(dsn.getOptions().get(TIMEOUT_OPTION)));
        return httpConnection;
    }

    protected Connection createUdpConnection(Dsn dsn) {
        int port = dsn.getPort() != -1 ? dsn.getPort() : UdpConnection.DEFAULT_UDP_PORT;
        UdpConnection udpConnection = new UdpConnection(dsn.getHost(), port, dsn.getPublicKey(), dsn.getSecretKey());
        udpConnection.setMarshaller(createMarshaller(dsn));
        return udpConnection;
    }

    protected Marshaller createMarshaller(Dsn dsn) {
        JsonMarshaller marshaller = new JsonMarshaller();

        // Set JSON marshaller bindings
        StackTraceInterfaceBinding stackTraceBinding = new StackTraceInterfaceBinding();
        stackTraceBinding.setRemoveCommonFramesWithEnclosing(dsn.getOptions().containsKey(HIDE_COMMON_FRAMES_OPTION));
        stackTraceBinding.setNotInAppFrames(getNotInAppFrames());

        marshaller.addInterfaceBinding(StackTraceInterface.class, stackTraceBinding);
        marshaller.addInterfaceBinding(ExceptionInterface.class, new ExceptionInterfaceBinding());
        marshaller.addInterfaceBinding(MessageInterface.class, new MessageInterfaceBinding());
        HttpInterfaceBinding httpBinding = new HttpInterfaceBinding();
        //TODO: Add a way to clean the HttpRequest
        //httpBinding.
        marshaller.addInterfaceBinding(HttpInterface.class, httpBinding);

        // Set compression
        marshaller.setCompression(!dsn.getOptions().containsKey(NOCOMPRESSION_OPTION));

        return marshaller;
    }

    protected Collection<String> getNotInAppFrames() {
        return Arrays.asList("com.sun.",
                "java.",
                "javax.",
                "org.omg.",
                "sun.",
                "junit.",
                "com.intellij.rt.");
    }

    /**
     * Thread factory generating daemon threads with a custom priority.
     * <p>
     * Those (usually) low priority threads will allow to send event details to sentry concurrently without slowing
     * down the main application.
     * </p>
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private static final AtomicInteger POOL_NUMBER = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        private final int priority;

        private DaemonThreadFactory(int priority) {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            namePrefix = "pool-" + POOL_NUMBER.getAndIncrement() + "-thread-";
            this.priority = priority;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
            if (!t.isDaemon())
                t.setDaemon(true);
            if (t.getPriority() != priority)
                t.setPriority(priority);
            return t;
        }
    }
}
