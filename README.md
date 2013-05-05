# Raven

[![Build Status](https://secure.travis-ci.org/kencochrane/raven-java.png?branch=raven-3.x)](http://travis-ci.org/kencochrane/raven-java)

Raven is a Java client for [Sentry](https://www.getsentry.com/).
Besides the regular client you can use within your application code, this
project also provides tools allowing the most popular logging frameworks
to send the logs directly to sentry:

 - `java.util.logging` is supported out of the box.
 - `raven-log4j` adds the support for
 [log4j](https://logging.apache.org/log4j/1.2/).
 - `raven-log4j2` adds the support for
 [log4j2](https://logging.apache.org/log4j/2.x/).
 - `raven-logback` adds the support for [logback](http://logback.qos.ch/).

Raven supports both HTTP(S) and UDP transport of events.


## Sentry Protocol and supported versions
### Sentry Protocol versions
Since the version 3.0, Raven the versionning system is based on the
protocol version of Sentry. This means that Raven-3.x only supports
the version 3 of Sentry's protocol while Raven-4.x only supports
the version 4.

Sentry only supports the last two major releases of the protocol, for this
reason, only the last two major versions of Raven are maintained.

### Sentry versions

 - Sentry protocol v4 is not yet available (use Raven-4.x)
 - Sentry protocol v3 is available since Sentry 5.1 (use Raven-3.x)
 - Sentry protocol v2 is available since Sentry 2.0 (use Raven-2.x)


## Build and Installation
**See
[INSTALL.md](https://github.com/kencochrane/raven-java/blob/master/INSTALL.md)**


## Connection and protocol
It is possible to send events to Sentry over different protocols, depending
on the security and performance requirements.
So far Sentry accepts HTTP(S) and UDP which are both fully supported by
Raven.

### HTTP
The most common way send events to Sentry is through HTTP, this can be done by
using a DSN of this form:

    http://public:private@host:port/1

If not provided, the port will default to `80`.

### HTTPS
It is possible to use an encrypted connection to Sentry using HTTPS:

    https://public:private@host:port/1

If not provided, the port will default to `443`.

### HTTPS (naive)
If the certificate used over HTTPS is a wildcard certificate (which is not
handled by every version of Java), and the certificate isn't added to the
truststore, it is possible to add a protocol setting to tell the client to be
naive and ignore the hostname verification:

    naive+https://public:private@host:port/1

### UDP
It is possible to use a DSN with the UDP protocol:

    udp://public:private@host:port/1

If not provided the port will default to `9001`.

While being faster because there is no TCP and HTTP overhead, UDP doesn't wait
for a reply, and if a connection problem occurs, there will be no notification.


## Options
It is possible to enable some options by adding data to the query string of the
DSN:

    http://public:private@host:port/1?option1=value1&option2&option3=value3

Some options do not require a value, just being declared signifies that the
option is enabled.

### Async connection
In order to avoid performance issues due to a large amount of logs being
generated or a slow connection to the Sentry server, it is recommended to use
the asynchronous connection which will use a low priority thread pool to submit
events to Sentry.

To enable the async mode, add the `raven.async` option to your DSN:

    http://public:private@host:port/1?raven.async

#### Queue size (advanced)
The default queue used to store the not yet processed events doesn't have a
limit.
Depending on the environment (if the memory is sparse) it is important to be
able to control the size of that queue to avoid memory issues.

It is possible to set a maximum with the option `raven.async.queuesize`:

    http://public:private@host:port/1?raven.async&raven.async.queuesize=100

This means that if the connection to the Sentry server is down, only the first
100 events will be stored and be processed as soon as the server is back up.

#### Threads count (advanced)
By default the thread pool used by the async connection contains one thread per
processor available to the JVM (more threads wouldn't be useful).

It's possible to manually set the number of threads (for example if you want
only one thread) with the option `raven.async.threads`:

    http://public:private@host:port/1?raven.async&raven.async.threads=1

#### Threads priority (advanced)
As in most cases sending logs to Sentry isn't as important as an application
running smoothly, the threads have a
[minimal priority](http://docs.oracle.com/javase/6/docs/api/java/lang/Thread.html#MIN_PRIORITY).

It is possible to customise this value to increase the priority of those threads
with the option `raven.async.priority`:

    http://public:private@host:port/1?raven.async.priority=10&raven.async

### Inapp classes
Sentry differentiate `in_app` stack frames (which are directly related to your application)
and the "not `in_app`" ones.
This difference is visible in the Sentry web interface where only the `in_app`
frames are displayed by default.

#### Same frame as enclosing exception
Raven can use the `in_app` system to hide frames in the context of chained exceptions.

Usually when a StackTrace is printed, the result looks like this:

    HighLevelException: MidLevelException: LowLevelException
            at Main.a(Main.java:13)
            at Junk.main(Main.java:4)
    Caused by: MidLevelException: LowLevelException
            at Main.c(Main.java:23)
            at Main.b(Main.java:17)
            at Main.a(Main.java:11)
            ... 1 more
    Caused by: LowLevelException
            at Main.e(Main.java:30)
            at Main.d(Main.java:27)
            at Main.c(Main.java:21)
            ... 3 more

Some frames are replaced by the `... N more` line as they are the same frames
as in the enclosing exception.

To enable a similar behaviour from raven use the `raven.stacktrace.hidecommon` option.

    http://public:private@host:port/1?raven.stacktrace.hidecommon

#### Hide frames based on the class name
Raven can also mark some frames as `in_app` based on the name of the class.

This can be used to hide parts of the stacktrace that are irrelevant to the problem
for example the stack frames in the `java.util` package will not help determining
what the problem was and will just create a longer stacktrace.

Currently this is not configurable (see #49) and some packages are ignored by default:

- com.sun.*
- java.*
- javax.*
- org.omg.*
- sun.*
- junit.*
- com.intellij.rt.*

### Compression
By default the content sent to Sentry is compressed and encoded in base64 before
being sent.
This operation allows to send a smaller amount of data for each event.
However compressing and encoding the data adds a CPU and memory overhead which
might not be useful if the connection to Sentry is fast and reliable.

Depending on the limitations of the project (ie: a mobile application with a
limited connection, Sentry hosted on an external network), it can be interesting
to compress the data beforehand or not.

It's possible to manually enable/disable the compression with the option
`raven.compression`

    http://public:private@host:port/1?raven.compression=false

### Timeout (advanced)
To avoid blocking the thread because of a connection taking too much time, a
timeout can be set by the connection.

By default the connection will set up its own timeout, but it's possible to
manually set one with `raven.timeout` (in milliseconds):

    http://public:private@host:port/1?raven.timeout=10000


## History

- 4.0
    - Support of the Sentry protocol V4
- 3.0
    - Support of the Sentry protocol V3
    - Rewritten
    - Added log4j2 appender
    - Support of JNDI
- 2.0
    - Version increment to reduce confusion about releases
    - Added Logback appender (thanks to [ccouturi](https://github.com/ccouturi))
- 1.0
    - Rewrite
    - Support tags
    - Added support for JSON processors (see bundled `ServletJSONProcessor`)
- 0.6
    - Added support for sending messages through UDP
- 0.5
    - Added async support
    - Fixed issue with parsing of path and port in DSN
- 0.4
    - Added the ability to get the SENTRY_DSN from the ENV
    - Added RavenClient.captureMessage
    - Added RavenClient.captureException
- 0.3
    - Added Maven support
    - Merged with log4sentry project by Kevin Wetzels
    - Added Proxy support
    - Added full stack trace to logs

- 0.2
    - code refactor and cleanup

- 0.1
    - initial version

## Contributors

- [Ken Cochrane](https://github.com/kencochrane)
- [Kevin Wetzels](https://github.com/roam)
- [David Cramer](https://github.com/dcramer)
- [Mark Philpot](https://github.com/griphiam)
- [Brad Chen](https://github.com/vvasabi)
- [ccouturi](https://github.com/ccouturi)
- [Colin Hebert](https://github.com/ColinHebert)
