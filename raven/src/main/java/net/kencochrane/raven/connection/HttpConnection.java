package net.kencochrane.raven.connection;

import net.kencochrane.raven.Dsn;
import net.kencochrane.raven.Raven;
import net.kencochrane.raven.event.Event;
import net.kencochrane.raven.exception.ConnectionException;
import net.kencochrane.raven.marshaller.Marshaller;
import net.kencochrane.raven.marshaller.json.JsonMarshaller;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Basic connection to a Sentry server, using HTTP and HTTPS.
 * <p>
 * It is possible to enable the "naive mode" to allow a connection over SSL using a certificate with a wildcard.
 * </p>
 */
public class HttpConnection extends AbstractConnection {
    private static final Logger logger = Logger.getLogger(HttpConnection.class.getCanonicalName());
    /**
     * HTTP Header for the user agent.
     */
    private static final String USER_AGENT = "User-Agent";
    /**
     * HTTP Header for the authentication to Sentry.
     */
    private static final String SENTRY_AUTH = "X-Sentry-Auth";
    /**
     * Default timeout of an HTTP connection to Sentry.
     */
    private static final int DEFAULT_TIMEOUT = 10000;
    /**
     * HostnameVerifier allowing wildcard certificates to work without adding them to the truststore.
     */
    private static final HostnameVerifier NAIVE_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession sslSession) {
            return true;
        }
    };
    /**
     * URL of the Sentry endpoint.
     */
    private final URL sentryUrl;
    /**
     * Marshaller used to transform and send the {@link Event} over a stream.
     */
    private Marshaller marshaller = new JsonMarshaller();
    /**
     * Timeout of an HTTP connection to Sentry.
     */
    private int timeout = DEFAULT_TIMEOUT;
    /**
     * Setting allowing to bypass the security system which requires wildcard certificates
     * to be added to the truststore.
     */
    private boolean bypassSecurity;

    public HttpConnection(URL sentryUrl, String publicKey, String secretKey) {
        super(publicKey, secretKey);
        this.sentryUrl = sentryUrl;
    }

    public static URL getSentryApiUrl(Dsn dsn) {
        try {
            String url = dsn.getUri().toString() + "api/" + dsn.getProjectId() + "/store/";
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Couldn't get a valid URL from the DSN.", e);
        }
    }

    private HttpURLConnection getConnection() {
        try {
            HttpURLConnection connection = (HttpURLConnection) sentryUrl.openConnection();
            if (bypassSecurity && connection instanceof HttpsURLConnection) {
                ((HttpsURLConnection) connection).setHostnameVerifier(NAIVE_VERIFIER);
            }
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(timeout);
            connection.setRequestProperty(USER_AGENT, Raven.NAME);
            connection.setRequestProperty(SENTRY_AUTH, getAuthHeader());
            return connection;
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't set up a connection to the sentry server.", e);
        }
    }

    @Override
    public void doSend(Event event) {
        HttpURLConnection connection = getConnection();
        try {
            connection.connect();
            OutputStream outputStream = connection.getOutputStream();
            marshaller.marshall(event, outputStream);
            outputStream.close();
            connection.getInputStream().close();
        } catch (IOException e) {
            if (connection.getErrorStream() != null) {
                logger.log(Level.SEVERE, getErrorMessageFromStream(connection.getErrorStream()), e);
            } else {
                throw new ConnectionException("An exception occurred while submitting the event to the sentry server."
                        , e);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String getErrorMessageFromStream(InputStream errorStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream));
        StringBuilder sb = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line).append("\n");

        } catch (Exception e2) {
            logger.log(Level.SEVERE, "Exception while reading the error message from the connection.", e2);
        }
        return sb.toString();
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void setMarshaller(Marshaller marshaller) {
        this.marshaller = marshaller;
    }

    public void setBypassSecurity(boolean bypassSecurity) {
        this.bypassSecurity = bypassSecurity;
    }

    @Override
    public void close() throws IOException {
    }
}
