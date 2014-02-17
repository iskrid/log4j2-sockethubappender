/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.logging.log4j.sockethub;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Vector;

import org.apache.log4j.Category;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.SocketAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.helpers.Booleans;
import org.apache.logging.log4j.core.helpers.CyclicBuffer;
import org.apache.logging.log4j.core.layout.SerializedLayout;

/**
 * This is a SocketHubAppender implementation for Log4j2.
 * <p>
 * It's heavily based on the SocketHubAppender implementation for Log4j 1.2 and more a hacky port/wrapper to get the
 * code for 1.2 running in Log4j2 than a clean and good rewrite.
 * Because the SocketHubAppender is based on serializing a log event (the Log4j 1.2 LoggingEvent class) this class
 * and the related classes from Log4j 1.2 are still required to be compatible with visualization tools that can
 * connect to the SocketHubAppender like OTROS log viewer.
 * But this is a problem because these classes have to use the original package names which are already in use by the
 * Log4j 2 bridge for 1.2, and this bridge contains binary incompatible version of the LoggingEvent class.
 * Thus this project contains a copy of all relevant classes from Log4j 1.2, combined with a merging of the
 * Log4j2 beta9 bridge fpr Log4j 1.2.
 * <p>
 * A proper solution would be to make the LoggingEvent class in the Log4j2 bridge for Log4j 1.2 binary compatible
 * for the old version from 1.2 (this should be possible by addint the missing fields and related classes.
 * Additional this SocketHubAppender implementation may need some refactoring e.g. to take advantage of the new
 * manager concepts for appenders in Log4j2 and some general cleanup.
 *
 * <p>
 * Following the javadoc documentation from the Log4j 1.2 implementation of SocketHubAppender.
 *
 * <hr>
 *
 * Sends {@link LoggingEvent} objects to a set of remote log servers, usually a {@link SocketNode SocketNodes}.
 * <p>
 * Acts just like {@link SocketAppender} except that instead of connecting to a given remote log server,
 * <code>SocketHubAppender</code> accepts connections from the remote log servers as clients. It can accept more than
 * one connection. When a log event is received, the event is sent to the set of currently connected remote log servers.
 * Implemented this way it does not require any update to the configuration file to send data to another remote log
 * server. The remote log server simply connects to the host and port the <code>SocketHubAppender</code> is running on.
 * <p>
 * The <code>SocketHubAppender</code> does not store events such that the remote side will events that arrived after the
 * establishment of its connection. Once connected, events arrive in order as guaranteed by the TCP protocol.
 * <p>
 * This implementation borrows heavily from the {@link SocketAppender}.
 * <p>
 * The SocketHubAppender has the following characteristics:
 * <ul>
 * <p>
 * <li>If sent to a {@link SocketNode}, logging is non-intrusive as far as the log event is concerned. In other words,
 * the event will be logged with the same time stamp, {@link org.apache.log4j.NDC}, location info as if it were logged
 * locally.
 * <p>
 * <li><code>SocketHubAppender</code> does not use a layout. It ships a serialized {@link LoggingEvent} object to the
 * remote side.
 * <p>
 * <li><code>SocketHubAppender</code> relies on the TCP protocol. Consequently, if the remote side is reachable, then
 * log events will eventually arrive at remote client.
 * <p>
 * <li>If no remote clients are attached, the logging requests are simply dropped.
 * <p>
 * <li>Logging events are automatically <em>buffered</em> by the native TCP implementation. This means that if the link
 * to remote client is slow but still faster than the rate of (log) event production, the application will not be
 * affected by the slow network connection. However, if the network connection is slower then the rate of event
 * production, then the local application can only progress at the network rate. In particular, if the network link to
 * the the remote client is down, the application will be blocked.
 * <p>
 * On the other hand, if the network link is up, but the remote client is down, the client will not be blocked when
 * making log requests but the log events will be lost due to client unavailability.
 * <p>
 * The single remote client case extends to multiple clients connections. The rate of logging will be determined by the
 * slowest link.
 * <p>
 * <li>If the JVM hosting the <code>SocketHubAppender</code> exits before the <code>SocketHubAppender</code> is closed
 * either explicitly or subsequent to garbage collection, then there might be untransmitted data in the pipe which might
 * be lost. This is a common problem on Windows based systems.
 * <p>
 * To avoid lost data, it is usually sufficient to {@link #close} the <code>SocketHubAppender</code> either explicitly
 * or by calling the {@link org.apache.log4j.LogManager#shutdown} method before exiting the application.
 * </ul>
 * @author Mark Womack
 */
@Plugin(name = "SocketHub", category = "Core", elementType = "appender", printObject = true)
public final class Log4j2SocketHubAppender extends AbstractAppender {

  /**
   * The default port number of the ServerSocket will be created on.
   */
  static final int DEFAULT_PORT = 4560;

  private int port = DEFAULT_PORT;
  private Vector oosList = new Vector();
  private ServerMonitor serverMonitor = null;
  private boolean locationInfo = false;
  private CyclicBuffer<LoggingEvent> buffer = null;
  private String application;
  private boolean advertiseViaMulticastDNS;
  private ZeroConfSupport zeroConf;
  private boolean closed = false;
  private int bufferSize;

  /**
   * The MulticastDNS zone advertised by a SocketHubAppender
   */
  public static final String ZONE = "_log4j_obj_tcpaccept_appender.local.";
  private ServerSocket serverSocket;


  /**
   * Connects to remote server at <code>address</code> and <code>port</code>.
   */
  private Log4j2SocketHubAppender(String _Name, Filter _Filter, Layout<? extends Serializable> _Layout,
      boolean _IgnoreExceptions, int _port, boolean _locationInfo) {
    super(_Name, _Filter, _Layout, _IgnoreExceptions);
    port = _port;
    locationInfo = _locationInfo;
  }

  /**
   * Set up the socket server on the specified port.
   */
  public void activateOptions() {
    if (advertiseViaMulticastDNS) {
      zeroConf = new ZeroConfSupport(ZONE, port, getName());
      zeroConf.advertise();
    }
    startServer();
  }

  /**
   * Close this appender.
   * <p>
   * This will mark the appender as closed and call then {@link #cleanUp} method.
   */
  synchronized public void close() {
    if (closed)
      return;

    LOGGER.debug("closing SocketHubAppender " + getNameForLogger());
    this.closed = true;
    if (advertiseViaMulticastDNS) {
      zeroConf.unadvertise();
    }
    cleanUp();

    LOGGER.debug("SocketHubAppender " + getNameForLogger() + " closed");
  }

  /**
   * Release the underlying ServerMonitor thread, and drop the connections to all connected remote servers.
   */
  public void cleanUp() {
    // stop the monitor thread
    LOGGER.debug("stopping ServerSocket for SocketHubAppender " + getNameForLogger());
    serverMonitor.stopMonitor();
    serverMonitor = null;

    // close all of the connections
    LOGGER.debug("closing client connections for SocketHubAppender " + getNameForLogger());
    while (oosList.size() != 0) {
      ObjectOutputStream oos = (ObjectOutputStream)oosList.elementAt(0);
      if (oos != null) {
        try {
          oos.close();
        }
        catch (InterruptedIOException e) {
          Thread.currentThread().interrupt();
          LOGGER.error("could not close oos.", e);
        }
        catch (IOException e) {
          LOGGER.error("could not close oos.", e);
        }

        oosList.removeElementAt(0);
      }
    }
  }

  /**
   * Append an event to all of current connections.
   */
  public void append(LoggingEvent event) {
    if (event != null) {
      if (buffer != null) {
        buffer.add(event);
      }
    }

    // if no event or no open connections, exit now
    if ((event == null) || (oosList.size() == 0)) {
      return;
    }

    // loop through the current set of open connections, appending the event to each
    for (int streamCount = 0; streamCount < oosList.size(); streamCount++) {

      ObjectOutputStream oos = null;
      try {
        oos = (ObjectOutputStream)oosList.elementAt(streamCount);
      }
      catch (ArrayIndexOutOfBoundsException e) {
        // catch this, but just don't assign a value
        // this should not really occur as this method is
        // the only one that can remove oos's (besides cleanUp).
      }

      // list size changed unexpectedly? Just exit the append.
      if (oos == null)
        break;

      try {
        oos.writeObject(event);
        oos.flush();
        // Failing to reset the object output stream every now and
        // then creates a serious memory leak.
        // right now we always reset. TODO - set up frequency counter per oos?
        oos.reset();
      }
      catch (IOException e) {
        if (e instanceof InterruptedIOException) {
          Thread.currentThread().interrupt();
        }
        // there was an io exception so just drop the connection
        oosList.removeElementAt(streamCount);
        LOGGER.debug("dropped connection for SocketHubAppender " + getNameForLogger());

        // decrement to keep the counter in place (for loop always increments)
        streamCount--;
      }
    }
  }

  /**
   * The SocketHubAppender does not use a layout. Hence, this method returns <code>false</code>.
   */
  public boolean requiresLayout() {
    return false;
  }

  /**
   * The <b>Port</b> option takes a positive integer representing the port where the server is waiting for connections.
   */
  public void setPort(int _port) {
    port = _port;
  }

  /**
   * The <b>App</b> option takes a string value which should be the name of the application getting logged. If property
   * was already set (via system property),
   * don't set here.
   */
  public void setApplication(String lapp) {
    this.application = lapp;
  }

  /**
   * Returns value of the <b>Application</b> option.
   */
  public String getApplication() {
    return application;
  }

  /**
   * Returns value of the <b>Port</b> option.
   */
  public int getPort() {
    return port;
  }

  /**
   * The <b>BufferSize</b> option takes a positive integer representing the number of events this appender will buffer
   * and send to newly connected clients.
   */
  public void setBufferSize(int _bufferSize) {
    bufferSize = _bufferSize;
    buffer = new CyclicBuffer(LoggingEvent.class, _bufferSize);
  }

  /**
   * Returns value of the <b>bufferSize</b> option.
   */
  public int getBufferSize() {
    return bufferSize;
  }

  /**
   * The <b>LocationInfo</b> option takes a boolean value. If true, the information sent to the remote host will include
   * location information. By default no
   * location information is sent to the server.
   */
  public void setLocationInfo(boolean _locationInfo) {
    locationInfo = _locationInfo;
  }

  /**
   * Returns value of the <b>LocationInfo</b> option.
   */
  public boolean getLocationInfo() {
    return locationInfo;
  }

  public void setAdvertiseViaMulticastDNS(boolean advertiseViaMulticastDNS) {
    this.advertiseViaMulticastDNS = advertiseViaMulticastDNS;
  }

  public boolean isAdvertiseViaMulticastDNS() {
    return advertiseViaMulticastDNS;
  }

  /**
   * Start the ServerMonitor thread.
   */
  private void startServer() {
    serverMonitor = new ServerMonitor(port, oosList);
  }

  /**
   * Creates a server socket to accept connections.
   * @param socketPort port on which the socket should listen, may be zero.
   * @return new socket.
   * @throws IOException IO error when opening the socket.
   */
  protected ServerSocket createServerSocket(final int socketPort) throws IOException {
    LOGGER.debug("create server socket for SocketHubAppender " + getNameForLogger());
    return new ServerSocket(socketPort);
  }

  /**
   * This class is used internally to monitor a ServerSocket and register new connections in a vector passed in the
   * constructor.
   */
  private class ServerMonitor implements Runnable {

    private int port;
    private Vector oosList;
    private boolean keepRunning;
    private Thread monitorThread;

    /**
     * Create a thread and start the monitor.
     */
    public ServerMonitor(int _port, Vector _oosList) {
      port = _port;
      oosList = _oosList;
      keepRunning = true;
      monitorThread = new Thread(this);
      monitorThread.setDaemon(true);
      monitorThread.setName("SocketHubAppender-Monitor-" + port);
      monitorThread.start();
    }

    /**
     * Stops the monitor. This method will not return until the thread has finished executing.
     */
    public synchronized void stopMonitor() {
      if (keepRunning) {
        LOGGER.debug("server monitor thread shutting down for SocketHubAppender " + getNameForLogger());
        keepRunning = false;
        try {
          if (serverSocket != null) {
            serverSocket.close();
            serverSocket = null;
          }
        }
        catch (IOException ioe) {}

        try {
          monitorThread.join();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          // do nothing?
        }

        // release the thread
        monitorThread = null;
        LOGGER.debug("server monitor thread shut down for SocketHubAppender " + getNameForLogger());
      }
    }

    private void sendCachedEvents(ObjectOutputStream stream) throws IOException {
      if (buffer != null) {
        LoggingEvent[] events = buffer.removeAll();
        for (LoggingEvent event : events) {
          stream.writeObject(event);
        }
        stream.flush();
        stream.reset();
      }
    }

    /**
     * Method that runs, monitoring the ServerSocket and adding connections as they connect to the socket.
     */
    @Override
    public void run() {

      serverSocket = null;

      boolean socketSuccess = false;
      int socketFailureCount = 0;
      while (!socketSuccess) {
        try {
          serverSocket = createServerSocket(port);
          serverSocket.setSoTimeout(1000);
          socketSuccess = true;
        }
        catch (Exception e) {
          if (e instanceof InterruptedIOException || e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          final int MAX_SOCKET_FAIL_COUNT = 100;
          if (socketFailureCount < MAX_SOCKET_FAIL_COUNT) {
            socketFailureCount++;
            LOGGER.debug("exception setting timeout on socket creation, but trying again (" + socketFailureCount + "/" + MAX_SOCKET_FAIL_COUNT + ") "
                + "for SocketHubAppender " + getNameForLogger(), e);
            try {
              Thread.sleep(100);
            }
            catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
          }
          else {
            LOGGER.error("exception setting timeout on socket creation, shutting down server socket for SocketHubAppender " + getNameForLogger(), e);
            keepRunning = false;
            return;
          }
        }
      }

      try {
        try {
          serverSocket.setSoTimeout(1000);
        }
        catch (SocketException e) {
          LOGGER.debug("exception setting timeout, shutting down server socket for SocketHubAppender " + getNameForLogger(), e);
          return;
        }

        while (keepRunning) {
          Socket socket = null;
          try {
            socket = serverSocket.accept();
          }
          catch (InterruptedIOException e) {
            // timeout occurred, so just loop
          }
          catch (SocketException e) {
            LOGGER.debug("exception accepting socket, shutting down server socket for SocketHubAppender " + getNameForLogger(), e);
            keepRunning = false;
          }
          catch (IOException e) {
            LOGGER.error("exception accepting socket.", e);
          }

          // if there was a socket accepted
          if (socket != null) {
            try {
              InetAddress remoteAddress = socket.getInetAddress();
              LOGGER.debug("accepting connection from " + remoteAddress.getHostName()
                  + " (" + remoteAddress.getHostAddress() + ") for SocketHubAppender " + getNameForLogger());

              // create an ObjectOutputStream
              ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
              if (buffer != null && !buffer.isEmpty()) {
                sendCachedEvents(oos);
              }

              // add it to the oosList. OK since Vector is synchronized.
              oosList.addElement(oos);
            }
            catch (IOException e) {
              if (e instanceof InterruptedIOException) {
                Thread.currentThread().interrupt();
              }
              LOGGER.error("exception creating output stream on socket.", e);
            }
          }
        }
      }
      finally {
        // close the socket
        try {
          if (serverSocket != null) {
            serverSocket.close();
          }
        }
        catch (InterruptedIOException e) {
          Thread.currentThread().interrupt();
        }
        catch (IOException e) {
          // do nothing with it?
        }
      }
    }
  }

  @Override
  public void append(LogEvent pEvent) {
    org.apache.log4j.Level level = null;
    if (pEvent.getLevel()==Level.TRACE) {
      level = org.apache.log4j.Level.TRACE;
    }
    else if (pEvent.getLevel()==Level.DEBUG) {
      level = org.apache.log4j.Level.DEBUG;
    }
    else if (pEvent.getLevel()==Level.INFO) {
      level = org.apache.log4j.Level.INFO;
    }
    else if (pEvent.getLevel()==Level.WARN) {
      level = org.apache.log4j.Level.WARN;
    }
    else if (pEvent.getLevel()==Level.ERROR) {
      level = org.apache.log4j.Level.ERROR;
    }
    else if (pEvent.getLevel()==Level.FATAL) {
      level = org.apache.log4j.Level.FATAL;
    }
    else {
      // fallback
      level = org.apache.log4j.Level.ERROR;
    }
    ThrowableInformation throwableInformation = null;
    if (pEvent.getThrown() != null) {
      throwableInformation = new ThrowableInformation(pEvent.getThrown());
    }
    LoggingEvent loggingEvent = new LoggingEvent(pEvent.getFQCN(),
        Category.getInstance(pEvent.getFQCN()),
        pEvent.getMillis(),
        level,
        pEvent.getMessage(),
        pEvent.getThreadName(),
        throwableInformation,
        null,
        null,
        pEvent.getContextMap()
        );

    // set up location info if requested
    if (locationInfo) {
      loggingEvent.getLocationInformation();
    }

    this.append(loggingEvent);
  }

  @Override
  public void start() {
    LOGGER.debug("SocketHubAppender START: " + getNameForLogger());
    super.start();
    activateOptions();
  }

  @Override
  public void stop() {
    LOGGER.debug("SocketHubAppender STOP: " + getNameForLogger());
    close();
    super.stop();
  }

  private String getNameForLogger() {
    return getName() + " (port: " + this.port + ")";
  }

  @PluginFactory
  public static Log4j2SocketHubAppender createAppender(
      @PluginAttribute("port") final String _portNum,
      @PluginAttribute("locationInfo") final String _locationInfo,
      @PluginAttribute("name") final String _name,
      @PluginAttribute("ignoreExceptions") final String _ignore,
      @PluginElement("Layout") Layout<? extends Serializable> _layout,
      @PluginElement("Filters") final Filter _filter,
      @PluginConfiguration final Configuration _config) {

    boolean locationInfo = Booleans.parseBoolean(_locationInfo, false);
    final boolean ignoreExceptions = Booleans.parseBoolean(_ignore, true);
    final int port = AbstractAppender.parseInt(_portNum, 0);
    if (_layout == null) {
      _layout = SerializedLayout.createLayout();
    }

    if (_name == null) {
      LOGGER.error("No name provided for SocketAppender");
      return null;
    }

    return new Log4j2SocketHubAppender(_name, _filter, _layout, ignoreExceptions, port, locationInfo);
  }

}
