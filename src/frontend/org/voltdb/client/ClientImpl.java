/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.client;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.voltdb.VoltTable;
import org.voltdb.messaging.FastSerializer;
import org.voltdb.utils.DBBPool.BBContainer;

/**
 *  A client that connects to one or more nodes in a VoltCluster
 *  and provides methods to call stored procedures and receive
 *  responses.
 */
final class ClientImpl implements Client {

    private final AtomicLong m_handle = new AtomicLong(Long.MIN_VALUE);

    private final int m_expectedOutgoingMessageSize;

    /****************************************************
                        Public API
     ****************************************************/

    /**
     * Clients may queue a wide variety of messages and a lot of them
     * so give them a large max arena size.
     */
    private static final int m_defaultMaxArenaSize = 134217728;
    private volatile boolean m_isShutdown = false;

    /** Create a new client without any initial connections. */
    ClientImpl() {
        this( 128, new int[] {
                m_defaultMaxArenaSize,//16
                m_defaultMaxArenaSize,//32
                m_defaultMaxArenaSize,//64
                m_defaultMaxArenaSize,//128
                m_defaultMaxArenaSize,//256
                m_defaultMaxArenaSize,//512
                m_defaultMaxArenaSize,//1024
                m_defaultMaxArenaSize,//2048
                m_defaultMaxArenaSize,//4096
                m_defaultMaxArenaSize,//8192
                m_defaultMaxArenaSize,//16384
                m_defaultMaxArenaSize,//32768
                m_defaultMaxArenaSize,//65536
                m_defaultMaxArenaSize,//131072
                m_defaultMaxArenaSize//262144
        },
        false,
        null);
    }

    /**
     * Create a new client without any initial connections.
     * Also provide a hint indicating the expected serialized size of
     * most outgoing procedure invocations. This helps size initial allocations
     * for serializing network writes
     * @param expectedOutgoingMessageSize Expected size of procedure invocations in bytes
     * @param maxArenaSizes Maximum size arenas in the memory pool should grow to
     * @param heavyweight Whether to use multiple or a single thread
     */
    ClientImpl(
            int expectedOutgoingMessageSize,
            int maxArenaSizes[],
            boolean heavyweight,
            StatsUploaderSettings statsSettings) {
        m_expectedOutgoingMessageSize = expectedOutgoingMessageSize;
        m_distributer = new Distributer(
                expectedOutgoingMessageSize,
                maxArenaSizes,
                heavyweight,
                statsSettings);
        m_distributer.addClientStatusListener(new CSL());
    }

     /**
     * Create a connection to another VoltDB node.
     * @param host
     * @param program
     * @param password
     * @throws UnknownHostException
     * @throws IOException
     */
    public void createConnection(String host, String program, String password)
        throws UnknownHostException, IOException
    {
        if (m_isShutdown) {
            throw new IOException("Client instance is shutdown");
        }
        final String subProgram = (program == null) ? "" : program;
        final String subPassword = (password == null) ? "" : password;
        m_distributer.createConnection(host, subProgram, subPassword);
    }

    /**
     * Synchronously invoke a procedure call blocking until a result is available.
     * @param procName class name (not qualified by package) of the procedure to execute.
     * @param parameters vararg list of procedure's parameter values.
     * @return array of VoltTable results.
     * @throws org.voltdb.client.ProcCallException
     * @throws NoConnectionsException
     */
    public final VoltTable[] callProcedure(String procName, Object... parameters)
        throws ProcCallException, NoConnectionsException
    {
        if (m_isShutdown) {
            throw new ProcCallException("Client instance is shutdown", null);
        }
        final SyncCallback cb = new SyncCallback();
        final ProcedureInvocation invocation =
              new ProcedureInvocation(m_handle.getAndIncrement(), procName, parameters);


        m_distributer.queue(
                invocation,
                cb,
                m_expectedOutgoingMessageSize,
                true);

        try {
            cb.waitForResponse();
        } catch (final InterruptedException e) {
            throw new ProcCallException("Interrupted while waiting for response", e);
        }
        m_lastCallInfo = cb.getResponse().getExtra();
        // cb.result() throws ProcCallException if procedure failed
        return cb.result();
    }

    /**
     * Asynchronously invoke a procedure call.
     * @param callback TransactionCallback that will be invoked with procedure results.
     * @param procName class name (not qualified by package) of the procedure to execute.
     * @param parameters vararg list of procedure's parameter values.
     * @return True if the procedure was queued and false otherwise
     */
    public final boolean callProcedure(ProcedureCallback callback, String procName, Object... parameters)
    throws NoConnectionsException {
        if (m_isShutdown) {
            return false;
        }
        return callProcedure(callback, m_expectedOutgoingMessageSize, procName, parameters);
    }

    @Override
    public int calculateInvocationSerializedSize(String procName,
            Object... parameters) {
        final ProcedureInvocation invocation =
            new ProcedureInvocation(0, procName, parameters);
        final FastSerializer fds = new FastSerializer();
        int size = 0;
        try {
            final BBContainer c = fds.writeObjectForMessaging(invocation);
            size = c.b.remaining();
            c.discard();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        return size;
    }

    @Override
    public final boolean callProcedure(
            ProcedureCallback callback,
            int expectedSerializedSize,
            String procName,
            Object... parameters)
            throws NoConnectionsException {
        if (m_isShutdown) {
            return false;
        }
        ProcedureInvocation invocation =
            new ProcedureInvocation(m_handle.getAndIncrement(), procName, parameters);

        return m_distributer.queue(invocation, callback, expectedSerializedSize, false);
    }

    public void drain() throws NoConnectionsException {
        if (m_isShutdown) {
            return;
        }
        m_distributer.drain();
    }

    /**
     * Get the string value returned with the previous procedure call.
     * @return The string value returned with the previous call.
     */
    public String getInfoForPreviousCall() {
        return m_lastCallInfo;
    }

    /**
     * Shutdown the client closing all network connections and release
     * all memory resources.
     * @throws InterruptedException
     */
    public void close() throws InterruptedException {
        m_isShutdown = true;
        synchronized (m_backpressureLock) {
            m_backpressureLock.notifyAll();
        }
        m_distributer.shutdown();
    }

    public void addClientStatusListener(ClientStatusListener listener) {
        m_distributer.addClientStatusListener(listener);
    }

    public boolean removeClientStatusListener(ClientStatusListener listener) {
        return m_distributer.removeClientStatusListener(listener);
    }

    public void backpressureBarrier() throws InterruptedException {
        if (m_isShutdown) {
            return;
        }
        if (m_backpressure) {
            synchronized (m_backpressureLock) {
                if (m_backpressure) {
                    while (m_backpressure && !m_isShutdown) {
                            m_backpressureLock.wait();
                    }
                }
            }
        }
    }

    private class CSL implements ClientStatusListener {

        @Override
        public void backpressure(boolean status) {
            synchronized (m_backpressureLock) {
                if (status) {
                    m_backpressure = true;
                } else {
                    m_backpressure = false;
                    m_backpressureLock.notifyAll();
                }
            }
        }

        @Override
        public void connectionLost(String hostname, int connectionsLeft) {
            if (connectionsLeft == 0) {
                //Wake up client and let it attempt to queue work
                //and then fail with a NoConnectionsException
                synchronized (m_backpressureLock) {
                    m_backpressure = false;
                    m_backpressureLock.notifyAll();
                }
            }
        }

    }
     /****************************************************
                        Implementation
     ****************************************************/



    static final Logger LOG = Logger.getLogger(ClientImpl.class.getName());  // Logger shared by client package.
    private final Distributer m_distributer;                             // de/multiplexes connections to a cluster
    private String m_lastCallInfo = null;
    private final Object m_backpressureLock = new Object();
    private boolean m_backpressure = false;

    @Override
    public VoltTable getIOStats() {
        return m_distributer.getConnectionStats(false);
    }

    @Override
    public VoltTable getIOStatsInterval() {
        return m_distributer.getConnectionStats(true);
    }

    @Override
    public Object[] getInstanceId() {
        return m_distributer.getInstanceId();
    }

    @Override
    public VoltTable getProcedureStats() {
        return m_distributer.getProcedureStats(false);
    }

    @Override
    public VoltTable getProcedureStatsInterval() {
        return m_distributer.getProcedureStats(false);
    }

    @Override
    public String getBuildString() {
        return m_distributer.getBuildString();
    }
}
