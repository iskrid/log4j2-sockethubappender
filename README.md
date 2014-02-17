SocketHubAppender for Log4j2
===================

### About

This is a SocketHubAppender implementation for Log4j2.

It's heavily based on the SocketHubAppender implementation for Log4j 1.2 and more a hacky port/wrapper to get the code for 1.2 running in Log4j2 than a clean and good rewrite.
Because the SocketHubAppender is based on serializing a log event (the Log4j 1.2 LoggingEvent class) this class and the related classes from Log4j 1.2 are still required to be compatible with visualization tools that can connect to the SocketHubAppender like OTROS log viewer.
But this is a problem because these classes have to use the original package names which are already in use by the Log4j 2 bridge for 1.2, and this bridge contains binary incompatible version of the LoggingEvent class.
Thus this project contains a copy of all relevant classes from Log4j 1.2, combined with a merging of the Log4j2 bridge for Log4j 1.2.

A proper solution would be to make the LoggingEvent class in the Log4j2 bridge for Log4j 1.2 binary compatible for the old version from 1.2 (this should be possible by adding the missing fields and related classes. Additional this SocketHubAppender implementation may need some refactoring e.g. to take advantage of the new manager concepts for appenders in Log4j2 and some general cleanup.

For more information about the SocketHubAppender from Log4j 1.2 see [SocketHubAppender for log4j](http://wiki.apache.org/logging-log4j/SocketHubAppender)

### How to use

* Build the jar using Maven 3 from the folder log4j-1.2-api-with-sockethubappender
* Add the jar to the classpath of your project together with the log4j2 dependencies
* Be sure not to include the original log4j-1.2-api jar from log4j2 because it is replaced by this jar (see above for reasons)

Configuration example:

    <SocketHub name="socketHubExample" port="7020">
      <SerializedLayout />
    </SocketHub>
