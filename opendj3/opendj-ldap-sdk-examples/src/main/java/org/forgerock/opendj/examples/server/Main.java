/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2009-2010 Sun Microsystems, Inc.
 *      Portions copyright 2011 ForgeRock AS
 */

package org.forgerock.opendj.examples.server;



import static org.forgerock.opendj.ldap.ErrorResultException.newErrorResult;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.forgerock.opendj.ldap.*;
import org.forgerock.opendj.ldap.requests.*;
import org.forgerock.opendj.ldap.responses.*;
import org.forgerock.opendj.ldif.*;



/**
 * An LDAP directory server which exposes data contained in an LDIF file. This
 * is implementation is very simple and is only intended as an example:
 * <ul>
 * <li>It does not support SSL connections
 * <li>It does not support StartTLS
 * <li>It does not support Abandon or Cancel requests
 * <li>Very basic authentication and authorization support.
 * </ul>
 * This example takes the following command line parameters:
 *
 * <pre>
 *  &lt;listenAddress> &lt;listenPort> [&lt;ldifFile>]
 * </pre>
 */
public final class Main
{
  /**
   * Proxy server connection factory implementation.
   */
  private static final class Store implements
      ServerConnectionFactory<LDAPClientContext, Integer>
  {
    private final class ServerConnectionImpl implements
        ServerConnection<Integer>
    {

      private ServerConnectionImpl(
          final LDAPClientContext clientContext)
      {
        // Nothing to do.
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleAbandon(final Integer requestContext,
          final AbandonRequest request)
          throws UnsupportedOperationException
      {
        // Not implemented.
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleAdd(
          final Integer requestContext,
          final AddRequest request,
          final ResultHandler<? super Result> resultHandler,
          final IntermediateResponseHandler intermediateResponseHandler)
          throws UnsupportedOperationException
      {
        // TODO: controls.
        entryLock.writeLock().lock();
        try
        {
          DN dn = request.getName();
          if (entries.containsKey(dn))
          {
            resultHandler
                .handleErrorResult(ErrorResultException
                    .newErrorResult(ResultCode.ENTRY_ALREADY_EXISTS,
                        "The entry " + dn.toString()
                            + " already exists"));
          }

          DN parent = dn.parent();
          if (!entries.containsKey(parent))
          {
            resultHandler.handleErrorResult(ErrorResultException
                .newErrorResult(ResultCode.NO_SUCH_OBJECT,
                    "The parent entry " + parent.toString()
                        + " does not exist"));
          }
          else
          {
            entries.put(dn, request);
            resultHandler.handleResult(Responses
                .newResult(ResultCode.SUCCESS));
          }
        }
        finally
        {
          entryLock.writeLock().unlock();
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleBind(
          final Integer requestContext,
          final int version,
          final BindRequest request,
          final ResultHandler<? super BindResult> resultHandler,
          final IntermediateResponseHandler intermediateResponseHandler)
          throws UnsupportedOperationException
      {
        if (request.getAuthenticationType() != ((byte) 0x80))
        {
          // TODO: SASL authentication not implemented.
          resultHandler.handleErrorResult(newErrorResult(
              ResultCode.PROTOCOL_ERROR,
              "non-SIMPLE authentication not supported: "
                  + request.getAuthenticationType()));
        }
        else
        {
          // TODO: always succeed.
          resultHandler.handleResult(Responses
              .newBindResult(ResultCode.SUCCESS));
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleCompare(
          final Integer requestContext,
          final CompareRequest request,
          final ResultHandler<? super CompareResult> resultHandler,
          final IntermediateResponseHandler intermediateResponseHandler)
          throws UnsupportedOperationException
      {
        // TODO:
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleConnectionClosed(
          final Integer requestContext, final UnbindRequest request)
      {
        // Nothing to do.
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleConnectionDisconnected(
          final ResultCode resultCode, final String message)
      {
        // Nothing to do.
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleConnectionError(final Throwable error)
      {
        // Nothing to do.
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleDelete(
          final Integer requestContext,
          final DeleteRequest request,
          final ResultHandler<? super Result> resultHandler,
          final IntermediateResponseHandler intermediateResponseHandler)
          throws UnsupportedOperationException
      {
        // TODO: controls.
        entryLock.writeLock().lock();
        try
        {
          // TODO: check for children.
          DN dn = request.getName();
          if (!entries.containsKey(dn))
          {
            resultHandler
                .handleErrorResult(ErrorResultException
                    .newErrorResult(ResultCode.NO_SUCH_OBJECT,
                        "The entry " + dn.toString()
                            + " does not exist"));
          }
          else
          {
            entries.remove(dn);
            resultHandler.handleResult(Responses
                .newResult(ResultCode.SUCCESS));
          }
        }
        finally
        {
          entryLock.writeLock().unlock();
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public <R extends ExtendedResult> void handleExtendedRequest(
          final Integer requestContext,
          final ExtendedRequest<R> request,
          final ResultHandler<? super R> resultHandler,
          final IntermediateResponseHandler intermediateResponseHandler)
          throws UnsupportedOperationException
      {
        // TODO: not implemented.
        resultHandler.handleErrorResult(newErrorResult(
            ResultCode.PROTOCOL_ERROR,
            "Extended request operation not supported"));
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleModify(
          final Integer requestContext,
          final ModifyRequest request,
          final ResultHandler<? super Result> resultHandler,
          final IntermediateResponseHandler intermediateResponseHandler)
          throws UnsupportedOperationException
      {
        // TODO: controls.
        // TODO: read lock is not really enough since concurrent updates may
        // still occur to the same entry.
        entryLock.readLock().lock();
        try
        {
          DN dn = request.getName();
          Entry entry = entries.get(dn);
          if (entry == null)
          {
            resultHandler
                .handleErrorResult(ErrorResultException
                    .newErrorResult(ResultCode.NO_SUCH_OBJECT,
                        "The entry " + dn.toString()
                            + " does not exist"));
          }

          Entry newEntry = new LinkedHashMapEntry(entry);
          for (Modification mod : request.getModifications())
          {
            ModificationType modType = mod.getModificationType();
            if (modType.equals(ModificationType.ADD))
            {
              // TODO: Reject empty attribute and duplicate values.
              newEntry.addAttribute(mod.getAttribute(), null);
            }
            else if (modType.equals(ModificationType.DELETE))
            {
              // TODO: Reject missing values.
              newEntry.removeAttribute(mod.getAttribute(), null);
            }
            else if (modType.equals(ModificationType.REPLACE))
            {
              newEntry.replaceAttribute(mod.getAttribute());
            }
            else
            {
              resultHandler
                  .handleErrorResult(newErrorResult(
                      ResultCode.PROTOCOL_ERROR,
                      "Modify request contains an unsupported modification type"));
              return;
            }
          }

          entries.put(dn, newEntry);
          resultHandler.handleResult(Responses
              .newResult(ResultCode.SUCCESS));
        }
        finally
        {
          entryLock.readLock().unlock();
        }
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleModifyDN(
          final Integer requestContext,
          final ModifyDNRequest request,
          final ResultHandler<? super Result> resultHandler,
          final IntermediateResponseHandler intermediateResponseHandler)
          throws UnsupportedOperationException
      {
        // TODO: not implemented.
        resultHandler.handleErrorResult(newErrorResult(
            ResultCode.PROTOCOL_ERROR,
            "ModifyDN request operation not supported"));
      }



      /**
       * {@inheritDoc}
       */
      @Override
      public void handleSearch(
          final Integer requestContext,
          final SearchRequest request,
          final SearchResultHandler resultHandler,
          final IntermediateResponseHandler intermediateResponseHandler)
          throws UnsupportedOperationException
      {
        // TODO: controls, limits, etc.
        entryLock.readLock().lock();
        try
        {
          DN dn = request.getName();
          Entry baseEntry = entries.get(dn);
          if (baseEntry == null)
          {
            resultHandler
                .handleErrorResult(ErrorResultException
                    .newErrorResult(ResultCode.NO_SUCH_OBJECT,
                        "The entry " + dn.toString()
                            + " does not exist"));
          }

          SearchScope scope = request.getScope();
          Filter filter = request.getFilter();
          Matcher matcher = filter.matcher();

          if (scope.equals(SearchScope.BASE_OBJECT))
          {
            if (matcher.matches(baseEntry).toBoolean())
            {
              sendEntry(request, resultHandler, baseEntry);
            }
          }
          else if (scope.equals(SearchScope.SINGLE_LEVEL))
          {
            sendEntry(request, resultHandler, baseEntry);

            NavigableMap<DN, Entry> subtree = entries.tailMap(dn,
                false);
            for (Entry entry : subtree.values())
            {
              DN childDN = entry.getName();
              if (childDN.isChildOf(dn))
              {
                if (!matcher.matches(entry).toBoolean())
                {
                  continue;
                }

                if (!sendEntry(request, resultHandler, entry))
                {
                  // Caller has asked to stop sending results.
                  break;
                }
              }
              else if (!childDN.isSubordinateOrEqualTo(dn))
              {
                // The remaining entries will be out of scope.
                break;
              }
            }
          }
          else if (scope.equals(SearchScope.WHOLE_SUBTREE))
          {
            NavigableMap<DN, Entry> subtree = entries.tailMap(dn);
            for (Entry entry : subtree.values())
            {
              DN childDN = entry.getName();
              if (childDN.isSubordinateOrEqualTo(dn))
              {
                if (!matcher.matches(entry).toBoolean())
                {
                  continue;
                }

                if (!sendEntry(request, resultHandler, entry))
                {
                  // Caller has asked to stop sending results.
                  break;
                }
              }
              else
              {
                // The remaining entries will be out of scope.
                break;
              }
            }
          }
          else
          {
            resultHandler
                .handleErrorResult(newErrorResult(
                    ResultCode.PROTOCOL_ERROR,
                    "Search request contains an unsupported search scope"));
            return;
          }

          resultHandler.handleResult(Responses
              .newResult(ResultCode.SUCCESS));
        }
        finally
        {
          entryLock.readLock().unlock();
        }
      }



      private boolean sendEntry(SearchRequest request,
          SearchResultHandler resultHandler, Entry entry)
      {
        // TODO: check filter, strip attributes.
        return resultHandler.handleEntry(Responses
            .newSearchResultEntry(entry));
      }
    }



    private final ConcurrentSkipListMap<DN, Entry> entries;

    private final ReentrantReadWriteLock entryLock = new ReentrantReadWriteLock();



    private Store(final ConcurrentSkipListMap<DN, Entry> entries)
    {
      this.entries = entries;
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public ServerConnection<Integer> handleAccept(
        final LDAPClientContext clientContext)
        throws ErrorResultException
    {
      return new ServerConnectionImpl(clientContext);
    }

  }



  /**
   * Main method.
   *
   * @param args
   *          The command line arguments: listen address, listen port, ldifFile
   */
  public static void main(final String[] args)
  {
    if (args.length != 3)
    {
      System.err.println("Usage: listenAddress listenPort ldifFile");
      System.exit(1);
    }

    // Parse command line arguments.
    final String localAddress = args[0];
    final int localPort = Integer.parseInt(args[1]);

    // Read the LDIF.
    InputStream ldif;
    try
    {
      ldif = new FileInputStream(args[2]);
    }
    catch (final FileNotFoundException e)
    {
      System.err.println(e.getMessage());
      System.exit(ResultCode.CLIENT_SIDE_PARAM_ERROR.intValue());
      return;
    }

    final LDIFEntryReader reader = new LDIFEntryReader(ldif);
    ConcurrentSkipListMap<DN, Entry> entries = new ConcurrentSkipListMap<DN, Entry>();
    try
    {
      while (reader.hasNext())
      {
        Entry entry = reader.readEntry();
        entries.put(entry.getName(), entry);
      }
    }
    catch (final IOException e)
    {
      System.err.println(e.getMessage());
      System.exit(ResultCode.CLIENT_SIDE_LOCAL_ERROR.intValue());
      return;
    }
    finally
    {
      try
      {
        reader.close();
      }
      catch (final IOException ignored)
      {
        // Ignore.
      }
    }

    // Quickly sanity check that every entry (except root entries) have a
    // parent.
    boolean isValid = true;
    for (DN dn : entries.keySet())
    {
      if (dn.size() > 1)
      {
        DN parent = dn.parent();
        if (!entries.containsKey(parent))
        {
          System.err.println("The entry \"" + dn.toString()
              + "\" does not have a parent");
          isValid = false;
        }
      }
    }
    if (!isValid)
    {
      System.exit(1);
    }

    // Create listener.
    final LDAPListenerOptions options = new LDAPListenerOptions()
        .setBacklog(4096);
    LDAPListener listener = null;
    try
    {
      listener = new LDAPListener(localAddress, localPort, new Store(
          entries), options);
      System.out.println("Press any key to stop the server...");
      System.in.read();
    }
    catch (final IOException e)
    {
      System.out.println("Error listening on " + localAddress + ":"
          + localPort);
      e.printStackTrace();
    }
    finally
    {
      if (listener != null)
      {
        listener.close();
      }
    }
  }



  private Main()
  {
    // Not used.
  }
}