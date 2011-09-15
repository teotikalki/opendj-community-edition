/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2011 ForgeRock AS.
 */

package org.opends.server.extensions;



import static org.opends.messages.ExtensionMessages.*;
import static org.opends.server.loggers.debug.DebugLogger.debugEnabled;
import static org.opends.server.protocols.ldap.LDAPConstants.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import javax.net.ssl.*;

import org.opends.messages.Message;
import org.opends.server.admin.server.ConfigurationChangeListener;
import org.opends.server.admin.std.server.*;
import org.opends.server.api.AuthenticationPolicy;
import org.opends.server.api.AuthenticationPolicyFactory;
import org.opends.server.api.AuthenticationPolicyState;
import org.opends.server.api.TrustManagerProvider;
import org.opends.server.config.ConfigException;
import org.opends.server.core.DirectoryServer;
import org.opends.server.loggers.debug.DebugLogger;
import org.opends.server.loggers.debug.DebugTracer;
import org.opends.server.protocols.asn1.ASN1Exception;
import org.opends.server.protocols.ldap.*;
import org.opends.server.tools.LDAPReader;
import org.opends.server.tools.LDAPWriter;
import org.opends.server.types.*;



/**
 * LDAP pass through authentication policy implementation.
 */
public final class LDAPPassThroughAuthenticationPolicyFactory implements
    AuthenticationPolicyFactory<LDAPPassThroughAuthenticationPolicyCfg>
{

  // TODO: retry operations transparently until all connections exhausted.
  // TODO: handle password policy response controls? AD?
  // TODO: periodically ping offline servers in order to detect when they come
  // back.

  /**
   * An LDAP connection which will be used in order to search for or
   * authenticate users.
   */
  static interface Connection extends Closeable
  {

    /**
     * Closes this connection.
     */
    @Override
    void close();



    /**
     * Returns the name of the user whose entry matches the provided search
     * criteria. This will return CLIENT_SIDE_NO_RESULTS_RETURNED/NO_SUCH_OBJECT
     * if no search results were returned, or CLIENT_SIDE_MORE_RESULTS_TO_RETURN
     * if too many results were returned.
     *
     * @param baseDN
     *          The search base DN.
     * @param scope
     *          The search scope.
     * @param filter
     *          The search filter.
     * @return The name of the user whose entry matches the provided search
     *         criteria.
     * @throws DirectoryException
     *           If the search returned no entries, more than one entry, or if
     *           the search failed unexpectedly.
     */
    ByteString search(DN baseDN, SearchScope scope, SearchFilter filter)
        throws DirectoryException;



    /**
     * Performs a simple bind for the user.
     *
     * @param username
     *          The user name (usually a bind DN).
     * @param password
     *          The user's password.
     * @throws DirectoryException
     *           If the credentials were invalid, or the authentication failed
     *           unexpectedly.
     */
    void simpleBind(ByteString username, ByteString password)
        throws DirectoryException;
  }



  /**
   * An interface for obtaining connections: users of this interface will obtain
   * a connection, perform a single operation (search or bind), and then close
   * it.
   */
  static interface ConnectionFactory
  {
    /**
     * Returns a connection which can be used in order to search for or
     * authenticate users.
     *
     * @return The connection.
     * @throws DirectoryException
     *           If an unexpected error occurred while attempting to obtain a
     *           connection.
     */
    Connection getConnection() throws DirectoryException;
  }



  /**
   * An interface for obtaining a connection factory for LDAP connections to a
   * named LDAP server.
   */
  static interface LDAPConnectionFactoryProvider
  {
    /**
     * Returns a connection factory which can be used for obtaining connections
     * to the specified LDAP server.
     *
     * @param host
     *          The LDAP server host name.
     * @param port
     *          The LDAP server port.
     * @param options
     *          The LDAP connection options.
     * @return A connection factory which can be used for obtaining connections
     *         to the specified LDAP server.
     */
    ConnectionFactory getLDAPConnectionFactory(String host, int port,
        LDAPPassThroughAuthenticationPolicyCfg options);
  }



  /**
   * The PTA design guarantees that connections are only used by a single thread
   * at a time, so we do not need to perform any synchronization.
   * <p>
   * Package private for testing.
   */
  static final class LDAPConnectionFactory implements ConnectionFactory
  {
    /**
     * LDAP connection implementation.
     */
    private final class LDAPConnection implements Connection
    {
      private final Socket plainSocket;
      private final Socket ldapSocket;
      private final LDAPWriter writer;
      private final LDAPReader reader;
      private int nextMessageID = 1;
      private boolean isClosed = false;



      private LDAPConnection(final Socket plainSocket, final Socket ldapSocket,
          final LDAPReader reader, final LDAPWriter writer)
      {
        this.plainSocket = plainSocket;
        this.ldapSocket = ldapSocket;
        this.reader = reader;
        this.writer = writer;
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void close()
      {
        /*
         * This method is intentionally a bit "belt and braces" because we have
         * seen far too many subtle resource leaks due to bugs within JDK,
         * especially when used in conjunction with SSL (e.g.
         * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=7025227).
         */
        if (isClosed)
        {
          return;
        }
        isClosed = true;

        // Send an unbind request.
        final LDAPMessage message = new LDAPMessage(nextMessageID++,
            new UnbindRequestProtocolOp());
        try
        {
          writer.writeMessage(message);
        }
        catch (final IOException e)
        {
          if (debugEnabled())
          {
            TRACER.debugCaught(DebugLogLevel.ERROR, e);
          }
        }

        // Close all IO resources.
        writer.close();
        reader.close();

        try
        {
          ldapSocket.close();
        }
        catch (final IOException e)
        {
          if (debugEnabled())
          {
            TRACER.debugCaught(DebugLogLevel.ERROR, e);
          }
        }

        try
        {
          plainSocket.close();
        }
        catch (final IOException e)
        {
          if (debugEnabled())
          {
            TRACER.debugCaught(DebugLogLevel.ERROR, e);
          }
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public ByteString search(final DN baseDN, final SearchScope scope,
          final SearchFilter filter) throws DirectoryException
      {
        // Create the search request and send it to the server.
        final SearchRequestProtocolOp searchRequest =
          new SearchRequestProtocolOp(
            ByteString.valueOf(baseDN.toString()), scope,
            DereferencePolicy.DEREF_ALWAYS, 1 /* size limit */,
            (timeoutMS / 1000), true /* types only */,
            RawFilter.create(filter), NO_ATTRIBUTES);
        sendRequest(searchRequest);

        // Read the responses from the server. We cannot fail-fast since this
        // could leave unread search response messages.
        byte opType;
        ByteString username = null;
        int resultCount = 0;

        do
        {
          final LDAPMessage responseMessage = readResponse();
          opType = responseMessage.getProtocolOpType();

          switch (opType)
          {
          case OP_TYPE_SEARCH_RESULT_ENTRY:
            final SearchResultEntryProtocolOp searchEntry = responseMessage
                .getSearchResultEntryProtocolOp();
            if (username == null)
            {
              username = ByteString.valueOf(searchEntry.getDN().toString());
            }
            resultCount++;
            break;

          case OP_TYPE_SEARCH_RESULT_REFERENCE:
            // The reference does not necessarily mean that there would have
            // been any matching results, so lets ignore it.
            break;

          case OP_TYPE_SEARCH_RESULT_DONE:
            final SearchResultDoneProtocolOp searchResult = responseMessage
                .getSearchResultDoneProtocolOp();

            final ResultCode resultCode = ResultCode.valueOf(searchResult
                .getResultCode());
            switch (resultCode)
            {
            case SUCCESS:
              // The search succeeded. Drop out of the loop and check that we
              // got a matching entry.
              break;

            case SIZE_LIMIT_EXCEEDED:
              // Multiple matching candidates.
              throw new DirectoryException(
                  ResultCode.CLIENT_SIDE_MORE_RESULTS_TO_RETURN,
                  ERR_LDAP_PTA_CONNECTION_SEARCH_SIZE_LIMIT.get(host, port,
                      String.valueOf(options.dn()), String.valueOf(baseDN),
                      String.valueOf(filter)));

            default:
              // The search failed for some reason.
              throw new DirectoryException(resultCode,
                  ERR_LDAP_PTA_CONNECTION_SEARCH_FAILED.get(host, port,
                      String.valueOf(options.dn()), String.valueOf(baseDN),
                      String.valueOf(filter), resultCode.getIntValue(),
                      resultCode.getResultCodeName(),
                      searchResult.getErrorMessage()));
            }

            break;

          default:
            // Check for disconnect notifications.
            handleUnexpectedResponse(responseMessage);
            break;
          }
        }
        while (opType != OP_TYPE_SEARCH_RESULT_DONE);

        if (resultCount > 1)
        {
          // Multiple matching candidates.
          throw new DirectoryException(
              ResultCode.CLIENT_SIDE_MORE_RESULTS_TO_RETURN,
              ERR_LDAP_PTA_CONNECTION_SEARCH_SIZE_LIMIT.get(host, port,
                  String.valueOf(options.dn()), String.valueOf(baseDN),
                  String.valueOf(filter)));
        }

        if (username == null)
        {
          // No matching entries found.
          throw new DirectoryException(
              ResultCode.CLIENT_SIDE_NO_RESULTS_RETURNED,
              ERR_LDAP_PTA_CONNECTION_SEARCH_NO_MATCHES.get(host, port,
                  String.valueOf(options.dn()), String.valueOf(baseDN),
                  String.valueOf(filter)));
        }

        return username;
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void simpleBind(final ByteString username,
          final ByteString password) throws DirectoryException
      {
        // Create the bind request and send it to the server.
        final BindRequestProtocolOp bindRequest = new BindRequestProtocolOp(
            username, 3, password);
        sendRequest(bindRequest);

        // Read the response from the server.
        final LDAPMessage responseMessage = readResponse();
        switch (responseMessage.getProtocolOpType())
        {
        case OP_TYPE_BIND_RESPONSE:
          final BindResponseProtocolOp bindResponse = responseMessage
              .getBindResponseProtocolOp();

          final ResultCode resultCode = ResultCode.valueOf(bindResponse
              .getResultCode());
          if (resultCode == ResultCode.SUCCESS)
          {
            // FIXME: need to look for things like password expiration
            // warning, reset notice, etc.
            return;
          }
          else
          {
            // The bind failed for some reason.
            throw new DirectoryException(resultCode,
                ERR_LDAP_PTA_CONNECTION_BIND_FAILED.get(host, port,
                    String.valueOf(options.dn()), String.valueOf(username),
                    resultCode.getIntValue(), resultCode.getResultCodeName(),
                    bindResponse.getErrorMessage()));
          }

        default:
          // Check for disconnect notifications.
          handleUnexpectedResponse(responseMessage);
          break;
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public String toString()
      {
        final StringBuilder builder = new StringBuilder();
        builder.append("LDAPConnection(");
        builder.append(String.valueOf(ldapSocket.getLocalSocketAddress()));
        builder.append(", ");
        builder.append(String.valueOf(ldapSocket.getRemoteSocketAddress()));
        builder.append(')');
        return builder.toString();
      }



      /**
       * {@inheritDoc}
       */
      @Override
      protected void finalize()
      {
        close();
      }



      private void handleUnexpectedResponse(final LDAPMessage responseMessage)
          throws DirectoryException
      {
        if (responseMessage.getProtocolOpType() == OP_TYPE_EXTENDED_RESPONSE)
        {
          final ExtendedResponseProtocolOp extendedResponse = responseMessage
              .getExtendedResponseProtocolOp();
          final String responseOID = extendedResponse.getOID();

          if ((responseOID != null)
              && responseOID.equals(OID_NOTICE_OF_DISCONNECTION))
          {
            throw new DirectoryException(ResultCode.valueOf(extendedResponse
                .getResultCode()), ERR_LDAP_PTA_CONNECTION_DISCONNECTING.get(
                host, port, String.valueOf(options.dn()),
                extendedResponse.getErrorMessage()));
          }
        }

        // Unexpected response type.
        throw new DirectoryException(ResultCode.CLIENT_SIDE_DECODING_ERROR,
            ERR_LDAP_PTA_CONNECTION_WRONG_RESPONSE.get(host, port,
                String.valueOf(options.dn()),
                String.valueOf(responseMessage.getProtocolOp())));
      }



      // Reads a response message and adapts errors to directory exceptions.
      private LDAPMessage readResponse() throws DirectoryException
      {
        final LDAPMessage responseMessage;
        try
        {
          responseMessage = reader.readMessage();
        }
        catch (final ASN1Exception e)
        {
          // ASN1 layer hides all underlying IO exceptions.
          if (e.getCause() instanceof SocketTimeoutException)
          {
            throw new DirectoryException(ResultCode.CLIENT_SIDE_TIMEOUT,
                ERR_LDAP_PTA_CONNECTION_TIMEOUT.get(host, port,
                    String.valueOf(options.dn())), e);
          }
          else if (e.getCause() instanceof IOException)
          {
            throw new DirectoryException(ResultCode.CLIENT_SIDE_SERVER_DOWN,
                ERR_LDAP_PTA_CONNECTION_OTHER_ERROR.get(host, port,
                    String.valueOf(options.dn()), e.getMessage()), e);
          }
          else
          {
            throw new DirectoryException(ResultCode.CLIENT_SIDE_DECODING_ERROR,
                ERR_LDAP_PTA_CONNECTION_DECODE_ERROR.get(host, port,
                    String.valueOf(options.dn()), e.getMessage()), e);
          }
        }
        catch (final LDAPException e)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_DECODING_ERROR,
              ERR_LDAP_PTA_CONNECTION_DECODE_ERROR.get(host, port,
                  String.valueOf(options.dn()), e.getMessage()), e);
        }
        catch (final SocketTimeoutException e)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_TIMEOUT,
              ERR_LDAP_PTA_CONNECTION_TIMEOUT.get(host, port,
                  String.valueOf(options.dn())), e);
        }
        catch (final IOException e)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_SERVER_DOWN,
              ERR_LDAP_PTA_CONNECTION_OTHER_ERROR.get(host, port,
                  String.valueOf(options.dn()), e.getMessage()), e);
        }

        if (responseMessage == null)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_SERVER_DOWN,
              ERR_LDAP_PTA_CONNECTION_CLOSED.get(host, port,
                  String.valueOf(options.dn())));
        }
        return responseMessage;
      }



      // Sends a request message and adapts errors to directory exceptions.
      private void sendRequest(final ProtocolOp request)
          throws DirectoryException
      {
        final LDAPMessage requestMessage = new LDAPMessage(nextMessageID++,
            request);
        try
        {
          writer.writeMessage(requestMessage);
        }
        catch (final IOException e)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_SERVER_DOWN,
              ERR_LDAP_PTA_CONNECTION_OTHER_ERROR.get(host, port,
                  String.valueOf(options.dn()), e.getMessage()), e);
        }
      }
    }



    private final String host;
    private final int port;
    private final LDAPPassThroughAuthenticationPolicyCfg options;
    private final int timeoutMS;



    /**
     * LDAP connection factory implementation is package private so that it can
     * be tested.
     *
     * @param host
     *          The server host name.
     * @param port
     *          The server port.
     * @param options
     *          The options (SSL).
     */
    LDAPConnectionFactory(final String host, final int port,
        final LDAPPassThroughAuthenticationPolicyCfg options)
    {
      this.host = host;
      this.port = port;
      this.options = options;

      // Normalize the timeoutMS to an integer (admin framework ensures that the
      // value is non-negative).
      this.timeoutMS = (int) Math.min(options.getConnectionTimeout(),
          Integer.MAX_VALUE);
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public Connection getConnection() throws DirectoryException
    {
      try
      {
        // Create the remote ldapSocket address.
        final InetAddress address = InetAddress.getByName(host);
        final InetSocketAddress socketAddress = new InetSocketAddress(address,
            port);

        // Create the ldapSocket and connect to the remote server.
        final Socket plainSocket = new Socket();
        Socket ldapSocket = null;
        LDAPReader reader = null;
        LDAPWriter writer = null;
        LDAPConnection ldapConnection = null;

        try
        {
          // Set ldapSocket options before connecting.
          plainSocket.setTcpNoDelay(options.isUseTCPNoDelay());
          plainSocket.setKeepAlive(options.isUseTCPKeepAlive());
          plainSocket.setSoTimeout(timeoutMS);

          // Connect the ldapSocket.
          plainSocket.connect(socketAddress, timeoutMS);

          if (options.isUseSSL())
          {
            // Obtain the optional configured trust manager which will be used
            // in order to determine the trust of the remote LDAP server.
            TrustManager[] tm = null;
            DN trustManagerDN = options.getTrustManagerProviderDN();
            if (trustManagerDN != null)
            {
              TrustManagerProvider<?> trustManagerProvider = DirectoryServer
                  .getTrustManagerProvider(trustManagerDN);
              if (trustManagerProvider != null)
              {
                tm = trustManagerProvider.getTrustManagers();
              }
            }

            // Create the SSL context and initialize it.
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null /* key managers */, tm, null /* rng */);

            // Create the SSL socket.
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(
                plainSocket, host, port, true);
            ldapSocket = sslSocket;

            sslSocket.setUseClientMode(true);
            if (!options.getSSLProtocol().isEmpty())
            {
              sslSocket.setEnabledProtocols(options.getSSLProtocol().toArray(
                  new String[0]));
            }
            if (!options.getSSLCipherSuite().isEmpty())
            {
              sslSocket.setEnabledCipherSuites(options.getSSLCipherSuite()
                  .toArray(new String[0]));
            }

            // Force TLS negotiation.
            sslSocket.startHandshake();
          }
          else
          {
            ldapSocket = plainSocket;
          }

          reader = new LDAPReader(ldapSocket);
          writer = new LDAPWriter(ldapSocket);

          ldapConnection = new LDAPConnection(plainSocket, ldapSocket, reader,
              writer);

          return ldapConnection;
        }
        finally
        {
          if (ldapConnection == null)
          {
            // Connection creation failed for some reason, so clean up IO
            // resources.
            if (reader != null)
            {
              reader.close();
            }
            if (writer != null)
            {
              writer.close();
            }

            if (ldapSocket != null)
            {
              try
              {
                ldapSocket.close();
              }
              catch (final IOException ignored)
              {
                // Ignore.
              }
            }

            if (ldapSocket != plainSocket)
            {
              try
              {
                plainSocket.close();
              }
              catch (final IOException ignored)
              {
                // Ignore.
              }
            }
          }
        }
      }
      catch (final UnknownHostException e)
      {
        if (debugEnabled())
        {
          TRACER.debugCaught(DebugLogLevel.ERROR, e);
        }
        throw new DirectoryException(ResultCode.CLIENT_SIDE_CONNECT_ERROR,
            ERR_LDAP_PTA_CONNECT_UNKNOWN_HOST.get(host, port,
                String.valueOf(options.dn()), host), e);
      }
      catch (final ConnectException e)
      {
        if (debugEnabled())
        {
          TRACER.debugCaught(DebugLogLevel.ERROR, e);
        }
        throw new DirectoryException(ResultCode.CLIENT_SIDE_CONNECT_ERROR,
            ERR_LDAP_PTA_CONNECT_ERROR.get(host, port,
                String.valueOf(options.dn()), port), e);
      }
      catch (final SocketTimeoutException e)
      {
        if (debugEnabled())
        {
          TRACER.debugCaught(DebugLogLevel.ERROR, e);
        }
        throw new DirectoryException(ResultCode.CLIENT_SIDE_TIMEOUT,
            ERR_LDAP_PTA_CONNECT_TIMEOUT.get(host, port,
                String.valueOf(options.dn())), e);
      }
      catch (final SSLException e)
      {
        if (debugEnabled())
        {
          TRACER.debugCaught(DebugLogLevel.ERROR, e);
        }
        throw new DirectoryException(ResultCode.CLIENT_SIDE_CONNECT_ERROR,
            ERR_LDAP_PTA_CONNECT_SSL_ERROR.get(host, port,
                String.valueOf(options.dn()), e.getMessage()), e);
      }
      catch (final Exception e)
      {
        if (debugEnabled())
        {
          TRACER.debugCaught(DebugLogLevel.ERROR, e);
        }
        throw new DirectoryException(ResultCode.CLIENT_SIDE_CONNECT_ERROR,
            ERR_LDAP_PTA_CONNECT_OTHER_ERROR.get(host, port,
                String.valueOf(options.dn()), e.getMessage()), e);
      }

    }



    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
      final StringBuilder builder = new StringBuilder();
      builder.append("LDAPConnectionFactory(");
      builder.append(host);
      builder.append(':');
      builder.append(port);
      builder.append(')');
      return builder.toString();
    }
  }



  /**
   * LDAP PTA policy implementation.
   */
  private final class PolicyImpl extends AuthenticationPolicy implements
      ConfigurationChangeListener<LDAPPassThroughAuthenticationPolicyCfg>
  {

    /**
     * A factory which returns pre-authenticated connections for searches.
     */
    private final class AuthenticatedConnectionFactory implements
        ConnectionFactory
    {

      private final ConnectionFactory factory;



      private AuthenticatedConnectionFactory(final ConnectionFactory factory)
      {
        this.factory = factory;
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public Connection getConnection() throws DirectoryException
      {
        final DN username = configuration.getMappedSearchBindDN();
        final String password = configuration.getMappedSearchBindPassword();

        final Connection connection = factory.getConnection();
        if (username != null && !username.isNullDN() && password != null
            && password.length() > 0)
        {
          try
          {
            connection.simpleBind(ByteString.valueOf(username.toString()),
                ByteString.valueOf(password));
          }
          catch (final DirectoryException e)
          {
            connection.close();
            throw e;
          }
        }
        return connection;
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public String toString()
      {
        final StringBuilder builder = new StringBuilder();
        builder.append("AuthenticationConnectionFactory(");
        builder.append(factory);
        builder.append(')');
        return builder.toString();
      }

    }



    /**
     * PTA connection pool.
     */
    private final class ConnectionPool implements ConnectionFactory, Closeable
    {

      /**
       * Pooled connection's intercept close and release connection back to the
       * pool.
       */
      private final class PooledConnection implements Connection
      {
        private final Connection connection;
        private boolean connectionIsClosed = false;



        private PooledConnection(final Connection connection)
        {
          this.connection = connection;
        }



        /**
         * {@inheritDoc}
         */
        @Override
        public void close()
        {
          if (!connectionIsClosed)
          {
            connectionIsClosed = true;

            // Guarded by PolicyImpl
            if (poolIsClosed)
            {
              connection.close();
            }
            else
            {
              connectionPool.offer(connection);
            }
            availableConnections.release();
          }
        }



        /**
         * {@inheritDoc}
         */
        @Override
        public ByteString search(final DN baseDN, final SearchScope scope,
            final SearchFilter filter) throws DirectoryException
        {
          try
          {
            return connection.search(baseDN, scope, filter);
          }
          catch (final DirectoryException e)
          {
            // Don't put the connection back in the pool if it has failed.
            closeConnectionOnFatalError(e);
            throw e;
          }
        }



        /**
         * {@inheritDoc}
         */
        @Override
        public void simpleBind(final ByteString username,
            final ByteString password) throws DirectoryException
        {
          try
          {
            connection.simpleBind(username, password);
          }
          catch (final DirectoryException e)
          {
            // Don't put the connection back in the pool if it has failed.
            closeConnectionOnFatalError(e);
            throw e;
          }
        }



        private void closeConnectionOnFatalError(final DirectoryException e)
        {
          if (isFatalResultCode(e.getResultCode()))
          {
            if (!connectionIsClosed)
            {
              connectionIsClosed = true;
              connection.close();
              availableConnections.release();
            }
          }
        }

      }



      // Guarded by PolicyImpl.lock.
      private boolean poolIsClosed = false;

      private final ConnectionFactory factory;
      private final int poolSize =
        Runtime.getRuntime().availableProcessors() * 2;
      private final Semaphore availableConnections = new Semaphore(poolSize);
      private final ConcurrentLinkedQueue<Connection> connectionPool =
        new ConcurrentLinkedQueue<Connection>();



      private ConnectionPool(final ConnectionFactory factory)
      {
        this.factory = factory;
      }



      /**
       * Release all connections: do we want to block?
       */
      @Override
      public void close()
      {
        // No need for synchronization as this can only be called with the
        // policy's exclusive lock.
        poolIsClosed = true;

        Connection connection;
        while ((connection = connectionPool.poll()) != null)
        {
          connection.close();
        }

        // Since we have the exclusive lock, there should be no more connections
        // in use.
        if (availableConnections.availablePermits() != poolSize)
        {
          throw new IllegalStateException(
              "Pool has remaining connections open after close");
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public Connection getConnection() throws DirectoryException
      {
        // This should only be called with the policy's shared lock.
        if (poolIsClosed)
        {
          throw new IllegalStateException("pool is closed");
        }

        availableConnections.acquireUninterruptibly();

        // There is either a pooled connection or we are allowed to create
        // one.
        Connection connection = connectionPool.poll();
        if (connection == null)
        {
          try
          {
            connection = factory.getConnection();
          }
          catch (final DirectoryException e)
          {
            availableConnections.release();
            throw e;
          }
        }

        return new PooledConnection(connection);
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public String toString()
      {
        final StringBuilder builder = new StringBuilder();
        builder.append("ConnectionPool(");
        builder.append(factory);
        builder.append(", poolSize=");
        builder.append(poolSize);
        builder.append(", inPool=");
        builder.append(connectionPool.size());
        builder.append(", available=");
        builder.append(availableConnections.availablePermits());
        builder.append(')');
        return builder.toString();
      }
    }



    /**
     * A simplistic two-way fail-over connection factory implementation.
     */
    private final class FailoverConnectionFactory implements ConnectionFactory,
        Closeable
    {
      private final LoadBalancer primary;
      private final LoadBalancer secondary;



      private FailoverConnectionFactory(final LoadBalancer primary,
          final LoadBalancer secondary)
      {
        this.primary = primary;
        this.secondary = secondary;
      }



      /**
       * Close underlying load-balancers.
       */
      @Override
      public void close()
      {
        primary.close();
        if (secondary != null)
        {
          secondary.close();
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public Connection getConnection() throws DirectoryException
      {
        if (secondary == null)
        {
          // No fail-over so just use the primary.
          return primary.getConnection();
        }
        else
        {
          try
          {
            return primary.getConnection();
          }
          catch (final DirectoryException e)
          {
            return secondary.getConnection();
          }
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public String toString()
      {
        final StringBuilder builder = new StringBuilder();
        builder.append("FailoverConnectionFactory(");
        builder.append(primary);
        builder.append(", ");
        builder.append(secondary);
        builder.append(')');
        return builder.toString();
      }

    }



    /**
     * A simplistic load-balancer connection factory implementation using
     * approximately round-robin balancing.
     */
    private final class LoadBalancer implements ConnectionFactory, Closeable
    {
      private final ConnectionPool[] factories;
      private final AtomicInteger nextIndex = new AtomicInteger();
      private final int maxIndex;



      private LoadBalancer(final ConnectionPool[] factories)
      {
        this.factories = factories;
        this.maxIndex = factories.length;
      }



      /**
       * Close underlying connection pools.
       */
      @Override
      public void close()
      {
        for (final ConnectionPool pool : factories)
        {
          pool.close();
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public Connection getConnection() throws DirectoryException
      {
        final int startIndex = getStartIndex();
        int index = startIndex;
        for (;;)
        {
          final ConnectionFactory factory = factories[index];

          try
          {
            return factory.getConnection();
          }
          catch (final DirectoryException e)
          {
            // Try the next index.
            if (++index == maxIndex)
            {
              index = 0;
            }

            // If all the factories have been tried then give up and throw the
            // exception.
            if (index == startIndex)
            {
              throw e;
            }
          }
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public String toString()
      {
        final StringBuilder builder = new StringBuilder();
        builder.append("LoadBalancer(");
        builder.append(nextIndex);
        for (final ConnectionFactory factory : factories)
        {
          builder.append(", ");
          builder.append(factory);
        }
        builder.append(')');
        return builder.toString();
      }



      // Determine the start index.
      private int getStartIndex()
      {
        // A round robin pool of one connection factories is unlikely in
        // practice and requires special treatment.
        if (maxIndex == 1)
        {
          return 0;
        }

        // Determine the next factory to use: avoid blocking algorithm.
        int oldNextIndex;
        int newNextIndex;
        do
        {
          oldNextIndex = nextIndex.get();
          newNextIndex = oldNextIndex + 1;
          if (newNextIndex == maxIndex)
          {
            newNextIndex = 0;
          }
        }
        while (!nextIndex.compareAndSet(oldNextIndex, newNextIndex));

        // There's a potential, but benign, race condition here: other threads
        // could jump in and rotate through the list before we return the
        // connection factory.
        return oldNextIndex;
      }

    }



    /**
     * LDAP PTA policy state implementation.
     */
    private final class StateImpl extends AuthenticationPolicyState
    {

      private final Entry userEntry;
      private ByteString cachedPassword = null;



      private StateImpl(final Entry userEntry)
      {
        this.userEntry = userEntry;
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void finalizeStateAfterBind() throws DirectoryException
      {
        if (cachedPassword != null)
        {
          // TODO: persist cached password if needed.
          cachedPassword = null;
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public AuthenticationPolicy getAuthenticationPolicy()
      {
        return PolicyImpl.this;
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public boolean passwordMatches(final ByteString password)
          throws DirectoryException
      {
        sharedLock.lock();
        try
        {
          // First of determine the user name to use when binding to the remote
          // directory.
          ByteString username = null;

          switch (configuration.getMappingPolicy())
          {
          case UNMAPPED:
            // The bind DN is the name of the user's entry.
            username = ByteString.valueOf(userEntry.getDN().toString());
            break;
          case MAPPED_BIND:
            // The bind DN is contained in an attribute in the user's entry.
            mapBind: for (final AttributeType at : configuration
                .getMappedAttribute())
            {
              final List<Attribute> attributes = userEntry.getAttribute(at);
              if (attributes != null && !attributes.isEmpty())
              {
                for (final Attribute attribute : attributes)
                {
                  if (!attribute.isEmpty())
                  {
                    username = attribute.iterator().next().getValue();
                    break mapBind;
                  }
                }
              }
            }

            if (username == null)
            {
              /*
               * The mapping attribute(s) is not present in the entry. This
               * could be a configuration error, but it could also be because
               * someone is attempting to authenticate using a bind DN which
               * references a non-user entry.
               */
              throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                  ERR_LDAP_PTA_MAPPING_ATTRIBUTE_NOT_FOUND.get(String
                      .valueOf(userEntry.getDN()), String.valueOf(configuration
                      .dn()), mappedAttributesAsString(configuration
                      .getMappedAttribute())));
            }

            break;
          case MAPPED_SEARCH:
            // A search against the remote directory is required in order to
            // determine the bind DN.

            // Construct the search filter.
            final LinkedList<SearchFilter> filterComponents =
              new LinkedList<SearchFilter>();
            for (final AttributeType at : configuration.getMappedAttribute())
            {
              final List<Attribute> attributes = userEntry.getAttribute(at);
              if (attributes != null && !attributes.isEmpty())
              {
                for (final Attribute attribute : attributes)
                {
                  for (final AttributeValue value : attribute)
                  {
                    filterComponents.add(SearchFilter.createEqualityFilter(at,
                        value));
                  }
                }
              }
            }

            if (filterComponents.isEmpty())
            {
              /*
               * The mapping attribute(s) is not present in the entry. This
               * could be a configuration error, but it could also be because
               * someone is attempting to authenticate using a bind DN which
               * references a non-user entry.
               */
              throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                  ERR_LDAP_PTA_MAPPING_ATTRIBUTE_NOT_FOUND.get(String
                      .valueOf(userEntry.getDN()), String.valueOf(configuration
                      .dn()), mappedAttributesAsString(configuration
                      .getMappedAttribute())));
            }

            final SearchFilter filter;
            if (filterComponents.size() == 1)
            {
              filter = filterComponents.getFirst();
            }
            else
            {
              filter = SearchFilter.createORFilter(filterComponents);
            }

            // Now search the configured base DNs, stopping at the first
            // success.
            for (final DN baseDN : configuration.getMappedSearchBaseDN())
            {
              Connection connection = null;
              try
              {
                connection = searchFactory.getConnection();
                username = connection.search(baseDN, SearchScope.WHOLE_SUBTREE,
                    filter);
              }
              catch (final DirectoryException e)
              {
                switch (e.getResultCode())
                {
                case NO_SUCH_OBJECT:
                case CLIENT_SIDE_NO_RESULTS_RETURNED:
                  // Ignore and try next base DN.
                  break;
                case CLIENT_SIDE_MORE_RESULTS_TO_RETURN:
                  // More than one matching entry was returned.
                  throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                      ERR_LDAP_PTA_MAPPED_SEARCH_TOO_MANY_CANDIDATES.get(
                          String.valueOf(userEntry.getDN()),
                          String.valueOf(configuration.dn()),
                          String.valueOf(baseDN), String.valueOf(filter)));
                default:
                  // We don't want to propagate this internal error to the
                  // client. We should log it and map it to a more appropriate
                  // error.
                  throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                      ERR_LDAP_PTA_MAPPED_SEARCH_FAILED.get(
                          String.valueOf(userEntry.getDN()),
                          String.valueOf(configuration.dn()),
                          e.getMessageObject()), e);
                }
              }
              finally
              {
                if (connection != null)
                {
                  connection.close();
                }
              }
            }

            if (username == null)
            {
              /*
               * No matching entries were found in the remote directory.
               */
              throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                  ERR_LDAP_PTA_MAPPED_SEARCH_NO_CANDIDATES.get(
                      String.valueOf(userEntry.getDN()),
                      String.valueOf(configuration.dn()),
                      String.valueOf(filter)));
            }

            break;
          }

          // Now perform the bind.
          Connection connection = null;
          try
          {
            connection = bindFactory.getConnection();
            connection.simpleBind(username, password);
            return true;
          }
          catch (final DirectoryException e)
          {
            switch (e.getResultCode())
            {
            case NO_SUCH_OBJECT:
            case INVALID_CREDENTIALS:
              return false;
            default:
              // We don't want to propagate this internal error to the
              // client. We should log it and map it to a more appropriate
              // error.
              throw new DirectoryException(
                  ResultCode.INVALID_CREDENTIALS,
                  ERR_LDAP_PTA_MAPPED_BIND_FAILED.get(
                      String.valueOf(userEntry.getDN()),
                      String.valueOf(configuration.dn()), e.getMessageObject()),
                  e);
            }
          }
          finally
          {
            if (connection != null)
            {
              connection.close();
            }
          }
        }
        finally
        {
          sharedLock.unlock();
        }
      }
    }



    // Guards against configuration changes.
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReadLock sharedLock = lock.readLock();
    private final WriteLock exclusiveLock = lock.writeLock();

    // Current configuration.
    private LDAPPassThroughAuthenticationPolicyCfg configuration;

    private FailoverConnectionFactory searchFactory = null;
    private FailoverConnectionFactory bindFactory = null;



    private PolicyImpl(
        final LDAPPassThroughAuthenticationPolicyCfg configuration)
    {
      initializeConfiguration(configuration);
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public ConfigChangeResult applyConfigurationChange(
        final LDAPPassThroughAuthenticationPolicyCfg configuration)
    {
      exclusiveLock.lock();
      try
      {
        closeConnections();
        initializeConfiguration(configuration);
      }
      finally
      {
        exclusiveLock.unlock();
      }
      return new ConfigChangeResult(ResultCode.SUCCESS, false);
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public AuthenticationPolicyState createAuthenticationPolicyState(
        final Entry userEntry, final long time) throws DirectoryException
    {
      // The current time is not needed for LDAP PTA.
      return new StateImpl(userEntry);
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public void finalizeAuthenticationPolicy()
    {
      exclusiveLock.lock();
      try
      {
        configuration.removeLDAPPassThroughChangeListener(this);
        closeConnections();
      }
      finally
      {
        exclusiveLock.unlock();
      }
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public DN getDN()
    {
      return configuration.dn();
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isConfigurationChangeAcceptable(
        final LDAPPassThroughAuthenticationPolicyCfg configuration,
        final List<Message> unacceptableReasons)
    {
      return LDAPPassThroughAuthenticationPolicyFactory.this
          .isConfigurationAcceptable(configuration, unacceptableReasons);
    }



    private void closeConnections()
    {
      exclusiveLock.lock();
      try
      {
        if (searchFactory != null)
        {
          searchFactory.close();
          searchFactory = null;
        }

        if (bindFactory != null)
        {
          bindFactory.close();
          bindFactory = null;
        }

      }
      finally
      {
        exclusiveLock.unlock();
      }
    }



    private void initializeConfiguration(
        final LDAPPassThroughAuthenticationPolicyCfg configuration)
    {
      this.configuration = configuration;

      // Use two pools per server: one for authentication (bind) and one for
      // searches. Even if the searches are performed anonymously we cannot use
      // the same pool, otherwise they will be performed as the most recently
      // authenticated user.

      // Create load-balancers for primary servers.
      final LoadBalancer primarySearchLoadBalancer;
      final LoadBalancer primaryBindLoadBalancer;

      Set<String> servers = configuration.getPrimaryRemoteLDAPServer();
      ConnectionPool[] searchPool = new ConnectionPool[servers.size()];
      ConnectionPool[] bindPool = new ConnectionPool[servers.size()];
      int index = 0;
      for (final String hostPort : servers)
      {
        final ConnectionFactory factory = newLDAPConnectionFactory(hostPort);
        searchPool[index] = new ConnectionPool(
            new AuthenticatedConnectionFactory(factory));
        bindPool[index++] = new ConnectionPool(factory);
      }
      primarySearchLoadBalancer = new LoadBalancer(searchPool);
      primaryBindLoadBalancer = new LoadBalancer(bindPool);

      // Create load-balancers for secondary servers.
      final LoadBalancer secondarySearchLoadBalancer;
      final LoadBalancer secondaryBindLoadBalancer;

      servers = configuration.getSecondaryRemoteLDAPServer();
      if (servers.isEmpty())
      {
        secondarySearchLoadBalancer = null;
        secondaryBindLoadBalancer = null;
      }
      else
      {
        searchPool = new ConnectionPool[servers.size()];
        bindPool = new ConnectionPool[servers.size()];
        index = 0;
        for (final String hostPort : servers)
        {
          final ConnectionFactory factory = newLDAPConnectionFactory(hostPort);
          searchPool[index] = new ConnectionPool(
              new AuthenticatedConnectionFactory(factory));
          bindPool[index++] = new ConnectionPool(factory);
        }
        secondarySearchLoadBalancer = new LoadBalancer(searchPool);
        secondaryBindLoadBalancer = new LoadBalancer(bindPool);
      }

      searchFactory = new FailoverConnectionFactory(primarySearchLoadBalancer,
          secondarySearchLoadBalancer);
      bindFactory = new FailoverConnectionFactory(primaryBindLoadBalancer,
          secondaryBindLoadBalancer);
    }



    private ConnectionFactory newLDAPConnectionFactory(final String hostPort)
    {
      // Validation already performed by admin framework.
      final int colonIndex = hostPort.lastIndexOf(":");
      final String hostname = hostPort.substring(0, colonIndex);
      final int port = Integer.parseInt(hostPort.substring(colonIndex + 1));
      return provider.getLDAPConnectionFactory(hostname, port, configuration);
    }

  }



  // Debug tracer for this class.
  private static final DebugTracer TRACER = DebugLogger.getTracer();

  /**
   * Attribute list for searches requesting no attributes.
   */
  static final LinkedHashSet<String> NO_ATTRIBUTES;

  static
  {
    NO_ATTRIBUTES = new LinkedHashSet<String>(1);
    NO_ATTRIBUTES.add("1.1");
  }

  // The provider which should be used by policies to create LDAP connections.
  private final LDAPConnectionFactoryProvider provider;

  /**
   * The default LDAP connection factory provider.
   */
  private static final LDAPConnectionFactoryProvider DEFAULT_PROVIDER =
    new LDAPConnectionFactoryProvider()
  {

    @Override
    public ConnectionFactory getLDAPConnectionFactory(final String host,
        final int port, final LDAPPassThroughAuthenticationPolicyCfg options)
    {
      return new LDAPConnectionFactory(host, port, options);
    }

  };



  /**
   * Determines whether or no a result code is expected to trigger the
   * associated connection to be closed immediately.
   *
   * @param resultCode
   *          The result code.
   * @return {@code true} if the result code is expected to trigger the
   *         associated connection to be closed immediately.
   */
  static boolean isFatalResultCode(final ResultCode resultCode)
  {
    switch (resultCode)
    {
    case BUSY:
    case UNAVAILABLE:
    case PROTOCOL_ERROR:
    case OTHER:
    case UNWILLING_TO_PERFORM:
    case OPERATIONS_ERROR:
    case TIME_LIMIT_EXCEEDED:
    case CLIENT_SIDE_CONNECT_ERROR:
    case CLIENT_SIDE_DECODING_ERROR:
    case CLIENT_SIDE_ENCODING_ERROR:
    case CLIENT_SIDE_LOCAL_ERROR:
    case CLIENT_SIDE_SERVER_DOWN:
    case CLIENT_SIDE_TIMEOUT:
      return true;
    default:
      return false;
    }
  }



  /**
   * Public default constructor used by the admin framework. This will use the
   * default LDAP connection factory provider.
   */
  public LDAPPassThroughAuthenticationPolicyFactory()
  {
    this(DEFAULT_PROVIDER);
  }



  /**
   * Package private constructor allowing unit tests to provide mock connection
   * implementations.
   *
   * @param provider
   *          The LDAP connection factory provider implementation which LDAP PTA
   *          authentication policies will use.
   */
  LDAPPassThroughAuthenticationPolicyFactory(
      final LDAPConnectionFactoryProvider provider)
  {
    this.provider = provider;
  }



  /**
   * {@inheritDoc}
   */
  @Override
  public AuthenticationPolicy createAuthenticationPolicy(
      final LDAPPassThroughAuthenticationPolicyCfg configuration)
      throws ConfigException, InitializationException
  {
    final PolicyImpl policy = new PolicyImpl(configuration);
    configuration.addLDAPPassThroughChangeListener(policy);
    return policy;
  }



  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isConfigurationAcceptable(
      final LDAPPassThroughAuthenticationPolicyCfg configuration,
      final List<Message> unacceptableReasons)
  {
    // Check that the port numbers are valid. We won't actually try and connect
    // to the server since they may not be available (hence we have fail-over
    // capabilities).
    boolean configurationIsAcceptable = true;

    for (String hostPort : configuration.getPrimaryRemoteLDAPServer())
    {
      configurationIsAcceptable &= isServerAddressValid(configuration,
          unacceptableReasons, hostPort);
    }

    for (String hostPort : configuration.getSecondaryRemoteLDAPServer())
    {
      configurationIsAcceptable &= isServerAddressValid(configuration,
          unacceptableReasons, hostPort);
    }

    return configurationIsAcceptable;
  }



  private static boolean isServerAddressValid(
      final LDAPPassThroughAuthenticationPolicyCfg configuration,
      final List<Message> unacceptableReasons, String hostPort)
  {
    final int colonIndex = hostPort.lastIndexOf(":");
    final int port = Integer.parseInt(hostPort.substring(colonIndex + 1));
    if (port < 1 || port > 65535)
    {
      if (unacceptableReasons != null)
      {
        Message msg = ERR_LDAP_PTA_INVALID_PORT_NUMBER.get(
            String.valueOf(configuration.dn()), hostPort);
        unacceptableReasons.add(msg);
      }
      return false;
    }
    return true;
  }



  private static String mappedAttributesAsString(
      Collection<AttributeType> attributes)
  {
    switch (attributes.size())
    {
    case 0:
      return "";
    case 1:
      return attributes.iterator().next().getNameOrOID();
    default:
      StringBuilder builder = new StringBuilder();
      Iterator<AttributeType> i = attributes.iterator();
      builder.append(i.next().getNameOrOID());
      while (i.hasNext())
      {
        builder.append(", ");
        builder.append(i.next().getNameOrOID());
      }
      return builder.toString();
    }
  }
}