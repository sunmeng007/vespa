// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * @class Communicator
 *
 * Responsible for doing RPC requests to VDS nodes.
 */

package com.yahoo.vespa.clustercontroller.core.rpc;

import com.yahoo.jrt.*;
import com.yahoo.vdslib.state.NodeState;
import com.yahoo.vdslib.state.ClusterState;
import com.yahoo.vdslib.state.State;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.clustercontroller.core.*;

import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * This class is not thread-safe.
 */
public class RPCCommunicator implements Communicator {

    public static final Logger log = Logger.getLogger(RPCCommunicator.class.getName());

    public static final int SET_DISTRIBUTION_STATES_RPC_VERSION = 3;
    public static final String SET_DISTRIBUTION_STATES_RPC_METHOD_NAME = "setdistributionstates";

    public static final int LEGACY_SET_SYSTEM_STATE2_RPC_VERSION = 2;
    public static final String LEGACY_SET_SYSTEM_STATE2_RPC_METHOD_NAME = "setsystemstate2";

    private final Timer timer;
    private final Supervisor supervisor;
    private double nodeStateRequestTimeoutIntervalMaxSeconds;
    private int nodeStateRequestTimeoutIntervalStartPercentage;
    private int nodeStateRequestTimeoutIntervalStopPercentage;
    private int nodeStateRequestRoundTripTimeMaxSeconds;
    private final int fleetControllerIndex;

    public static Supervisor createRealSupervisor() {
        return new Supervisor(new Transport());
    }

    public RPCCommunicator(
            final Supervisor supervisor,
            final Timer t,
            final int index,
            final int nodeStateRequestTimeoutIntervalMaxMs,
            final int nodeStateRequestTimeoutIntervalStartPercentage,
            final int nodeStateRequestTimeoutIntervalStopPercentage,
            final int nodeStateRequestRoundTripTimeMaxSeconds) {
        this.timer = t;
        this.fleetControllerIndex = index;
        checkArgument(nodeStateRequestTimeoutIntervalMaxMs > 0);
        checkArgument(nodeStateRequestTimeoutIntervalStartPercentage >= 0);
        checkArgument(nodeStateRequestTimeoutIntervalStartPercentage <= 100);
        checkArgument(nodeStateRequestTimeoutIntervalStopPercentage >= nodeStateRequestTimeoutIntervalStartPercentage);
        checkArgument(nodeStateRequestTimeoutIntervalStartPercentage <= 100);
        checkArgument(nodeStateRequestRoundTripTimeMaxSeconds >= 0);
        this.nodeStateRequestTimeoutIntervalMaxSeconds = nodeStateRequestTimeoutIntervalMaxMs / 1000D;
        this.nodeStateRequestTimeoutIntervalStartPercentage = nodeStateRequestTimeoutIntervalStartPercentage;
        this.nodeStateRequestTimeoutIntervalStopPercentage = nodeStateRequestTimeoutIntervalStopPercentage;
        this.nodeStateRequestRoundTripTimeMaxSeconds = nodeStateRequestRoundTripTimeMaxSeconds;
        this.supervisor = supervisor;
    }

    public void shutdown() {
        supervisor.transport().shutdown().join();
    }

    public Target getConnection(final NodeInfo node) {
        Target t = node.getConnection();
        if (t == null || !t.isValid()) {
            t = node.setConnection(
                    supervisor.connect(new Spec(node.getRpcAddress())));
        }
        return t;
    }

    public void doVersion0HandShake(Target connection, final NodeInfo node) {
        log.log(LogLevel.DEBUG, "Sending version 0 handshake request as version has been set down to 0 for " + node);
        Request req = new Request("vespa.storage.connect");
        req.parameters().add(new StringValue("storage/cluster." + node.getCluster().getName() + (node.isDistributor() ? "/distributor/" : "/storage/") + node.getNodeIndex()));
        connection.invokeAsync(req, 10.0, new RequestWaiter(){
            public void handleRequestDone(Request req) {
                if (req.isError()) {
                    log.log(LogLevel.WARNING, "Failed to do version 0 handshake towards " + node + ", " + req.errorCode() + ": " + req.errorMessage());
                } else if (!req.checkReturnTypes("i")) {
                    log.log(LogLevel.WARNING, "Wrong arguments returned from version 0 handshake attempt towards " + node);
                } else if (req.returnValues().get(0).asInt32() == 1) {
                    log.log(LogLevel.DEBUG, "Session already opened when handshaking towards " + node + ".");
                } else if (req.returnValues().get(0).asInt32() > 1) {
                    log.log(LogLevel.WARNING, "Handshaking attempt towards " + node + " failed with code " + req.returnValues().get(0).asInt32());
                }
            }
        });
        node.setConnectionVersion(0);
    }

    public void clearOldStoredNodeState(Target connection, final NodeInfo node) {
        log.log(LogLevel.DEBUG, "In case old node had stored a wanted state it is reporting, send a command to clear any unwanted stored state.");
        Request req = new Request("setnodestate");
        req.parameters().add(new StringValue(""));
        connection.invokeAsync(req, 10.0, new RequestWaiter() {
            public void handleRequestDone(Request req) {
                if (req.isError()) {
                    if (node.getReportedState().getState() != State.DOWN) {
                        log.log(LogLevel.WARNING, "Failed to clear nodestate on old node " + node + ", " + req.errorCode() + ": " + req.errorMessage());
                    }
                } else if (!req.checkReturnTypes("is")) {
                    log.log(LogLevel.WARNING, "Wrong arguments returned from version 0 setnodestate attempt to clear any unwanted state on " + node);
                }
            }
        });
    }

    @Override
    public void propagateOptions(final FleetControllerOptions options) {
        checkArgument(options.nodeStateRequestTimeoutMS > 0);
        checkArgument(options.nodeStateRequestTimeoutEarliestPercentage >= 0);
        checkArgument(options.nodeStateRequestTimeoutEarliestPercentage <= 100);
        checkArgument(options.nodeStateRequestTimeoutLatestPercentage
                      >= options.nodeStateRequestTimeoutEarliestPercentage);
        checkArgument(options.nodeStateRequestTimeoutLatestPercentage <= 100);
        checkArgument(options.nodeStateRequestRoundTripTimeMaxSeconds >= 0);
        this.nodeStateRequestTimeoutIntervalMaxSeconds = options.nodeStateRequestTimeoutMS / 1000.0;
        this.nodeStateRequestTimeoutIntervalStartPercentage = options.nodeStateRequestTimeoutEarliestPercentage;
        this.nodeStateRequestTimeoutIntervalStopPercentage = options.nodeStateRequestTimeoutLatestPercentage;
        this.nodeStateRequestRoundTripTimeMaxSeconds = options.nodeStateRequestRoundTripTimeMaxSeconds;
    }

    @Override
    public void getNodeState(NodeInfo node, Waiter<GetNodeStateRequest> externalWaiter) {
        Target connection = getConnection(node);
        if ( ! connection.isValid()) {
            log.log(LogLevel.DEBUG, "Connection to " + node.getRpcAddress() + " could not be created.");
        }
        // TODO remove this deprecated legacy stuff
        if (node.getVersion() == 0 && node.getConnectionVersion() > 0) {
            doVersion0HandShake(connection, node);
            clearOldStoredNodeState(connection, node);
        }
        NodeState currentState = node.getReportedState();
        Request req;
        if (node.getVersion() == 0) {
            req = new Request("getnodestate");
        } else {
            req = new Request(node.getVersion() == 1 ? "getnodestate2" : "getnodestate3");
            req.parameters().add(new StringValue(
                    currentState.getState().equals(State.DOWN) || node.getConnectionAttemptCount() > 0
                       ? "unknown" : currentState.serialize()));
            req.parameters().add(new Int32Value(generateNodeStateRequestTimeoutMs()));
            if (node.getVersion() > 1) {
                req.parameters().add(new Int32Value(fleetControllerIndex));
            }
        }

        RPCGetNodeStateRequest stateRequest = new RPCGetNodeStateRequest(node, req);
        RPCGetNodeStateWaiter waiter = new RPCGetNodeStateWaiter(stateRequest, externalWaiter, timer);

        double requestTimeoutSeconds =
            nodeStateRequestTimeoutIntervalMaxSeconds + nodeStateRequestRoundTripTimeMaxSeconds;

        connection.invokeAsync(req, requestTimeoutSeconds, waiter);
        node.setCurrentNodeStateRequest(stateRequest, timer.getCurrentTimeInMillis());
        node.lastRequestInfoConnection = connection;
    }

    @Override
    public void setSystemState(ClusterStateBundle stateBundle, NodeInfo node, Waiter<SetClusterStateRequest> externalWaiter) {
        final RPCSetClusterStateWaiter waiter = new RPCSetClusterStateWaiter(externalWaiter, timer);
        final ClusterState baselineState = stateBundle.getBaselineClusterState();

        Target connection = getConnection(node);
        if (!connection.isValid()) {
            log.log(LogLevel.DEBUG, "Connection to " + node.getRpcAddress() + " could not be created.");
            return;
        }
        final int nodeVersion = node.getVersion();
        // TODO remove this deprecated legacy stuff
        if (nodeVersion == 0 && node.getConnectionVersion() > 0) {
            doVersion0HandShake(connection, node);
            clearOldStoredNodeState(connection, node);
        }
        Request req;
        if (nodeVersion == 0) {
            req = new Request("setsystemstate");
            req.parameters().add(new StringValue(baselineState.toString(true)));
        } else if (nodeVersion <= 2) {
            req = new Request(LEGACY_SET_SYSTEM_STATE2_RPC_METHOD_NAME);
            req.parameters().add(new StringValue(baselineState.toString(false)));
        } else {
            req = new Request(SET_DISTRIBUTION_STATES_RPC_METHOD_NAME);
            SlimeClusterStateBundleCodec codec = new SlimeClusterStateBundleCodec();
            EncodedClusterStateBundle encodedBundle = codec.encode(stateBundle);
            Values v = req.parameters();
            v.add(new Int8Value(encodedBundle.getCompression().type().getCode()));
            v.add(new Int32Value(encodedBundle.getCompression().uncompressedSize()));
            v.add(new DataValue(encodedBundle.getCompression().data()));
        }

        log.log(LogLevel.DEBUG, () -> String.format("Sending '%s' RPC to %s for state version %d",
                req.methodName(), node.getRpcAddress(), stateBundle.getVersion()));
        RPCSetClusterStateRequest stateRequest = new RPCSetClusterStateRequest(node, req, baselineState.getVersion());
        waiter.setRequest(stateRequest);

        connection.invokeAsync(req, 60, waiter);
        node.setSystemStateVersionSent(baselineState);
    }

    // protected for testing.
    protected int generateNodeStateRequestTimeoutMs() {
        final double intervalFraction = Math.random();
        final double earliestTimeoutSeconds =
                nodeStateRequestTimeoutIntervalMaxSeconds * nodeStateRequestTimeoutIntervalStartPercentage / 100.0;
        final double latestTimeoutSeconds =
                nodeStateRequestTimeoutIntervalMaxSeconds * nodeStateRequestTimeoutIntervalStopPercentage / 100.0;
        final double interval = latestTimeoutSeconds - earliestTimeoutSeconds;
        final double timeoutSeconds = earliestTimeoutSeconds + intervalFraction * interval;
        return (int) (timeoutSeconds * 1000);
    }

}
