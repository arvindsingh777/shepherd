package com.devsda.platform.shephardcore.service;

import com.devsda.platform.shephardcore.dao.RegisterationDao;
import com.devsda.platform.shephardcore.dao.WorkflowOperationDao;
import com.devsda.platform.shepherd.constants.NodeState;
import com.devsda.platform.shepherd.exception.ClientInvalidRequestException;
import com.devsda.platform.shepherd.graphgenerator.DAGGenerator;
import com.devsda.platform.shephardcore.loader.JSONLoader;
import com.devsda.platform.shephardcore.model.*;
import com.devsda.platform.shephardcore.util.GraphUtil;
import com.devsda.platform.shepherd.model.*;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class ExecuteWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(ExecuteWorkflowService.class);

    @Inject
    private RegisterationDao registerationDao;

    @Inject
    private WorkflowOperationDao workflowOperationDao;


    /**
     * This method helps to execute workflow.
     * @param executeWorkflowRequest ExecuteWorkflow request from client.
     */
    public Integer executeWorkflow(ExecuteWorkflowRequest executeWorkflowRequest) throws Exception {
        return executeWorkflow(executeWorkflowRequest.getClientName(), executeWorkflowRequest.getEndpointName(), executeWorkflowRequest.getInitialPayload());
    }


    /**
     * This method helps to execute workflow.
     * @param clientName Execute workflow for given client.
     * @param endpointName Execute endpoint of above client.
     * @param initialPayload Initial payload, which will be consumed by root node of workflow.
     * @throws Exception
     */
    public Integer executeWorkflow(String clientName, String endpointName, Map<String, Object> initialPayload) throws Exception {


        // Get ClientId , and EndpointId.

        // Get endpoint details.
        EndpointDetails endpointDetails = registerationDao.getEndpointDetails(clientName, clientName);

        if(endpointDetails == null) {
            log.error(String.format("Client + Endpoint combination not registered. ClientName : %s. EndpointName : %s", clientName, endpointName));
            throw new ClientInvalidRequestException(String.format("Client + Endpoint combination not registered. ClientName : %s. EndpointName : %s", clientName, endpointName));
        }

        // Generate graph details.
        DAGGenerator dagGenerator = new DAGGenerator();
        Graph graph = dagGenerator.generateFromString(endpointDetails.getDAGGraph());

        // Load graph configurations.
        GraphConfiguration graphConfiguration = JSONLoader.loadFromStringifiedObject(endpointDetails.getEndpointDetails(), GraphConfiguration.class);

        log.info(String.format("Graph : %s. GraphConfiguration : %s", graph, graphConfiguration));

        // workflowOperationDao.

        // Graph
        executeGraph(graph, graphConfiguration);
    }


    /**
     * This method executes graph
     * @param graph directed acyclic graph
     * @param graphConfiguration configuration of all nodes under given graph
     * @throws InterruptedException
     * @throws ExecutionException
     */
    private Integer executeGraph(Graph graph, GraphConfiguration graphConfiguration) throws InterruptedException, ExecutionException {

        Map<String, NodeConfiguration> nodeNameToNodeConfigurationMapping = GraphUtil.getNodeNameToNodeConfigurationMapping(graphConfiguration);
        Map<String, TeamConfiguration> teamNameToTeamConfigurationMapping = GraphUtil.getTeamNameToTeamConfigurationMapping(graphConfiguration);

        Map<String, Node> nodeNameToNodeMapping = GraphUtil.getNodeNameToNodeMapping(graph);

        Map<String, List<String>> nodeToParentNodesMapping = GraphUtil.getNodeToParentNodesMapping(graph);

        String rootNode = GraphUtil.getRootNode(graph);

        ExecutorService executorService = Executors.newFixedThreadPool(10);
        log.info(String.format("Created thread-pool of count : %s", 10));

        NodeConfiguration rootNodeConfiguration = nodeNameToNodeConfigurationMapping.get(rootNode);
        ServerDetails rootNodeServerDetails = teamNameToTeamConfigurationMapping.get(nodeNameToNodeMapping.get(rootNode).getOwner()).getServerDetails();

        log.info(String.format("Submitting node : %s to thread-pool for execution", rootNode));
        Future<NodeResponse> rootNodeFuture = executorService.submit(new NodeExecutor(rootNodeConfiguration, rootNodeServerDetails));

        Deque<Future<NodeResponse>> futureObjects = new LinkedList<>();
        futureObjects.addFirst(rootNodeFuture);

        while(!futureObjects.isEmpty()) {

            Future<NodeResponse> thisFutureObject = futureObjects.removeFirst();

            try {
                NodeResponse nodeResponse = thisFutureObject.get(1000l, TimeUnit.MILLISECONDS);

                String nodeName = nodeResponse.getNodeName();
                log.info(String.format("Node : %s successfully completed", nodeName));

                nodeNameToNodeMapping.get(nodeName).setNodeState(NodeState.COMPLETED);

                // TODO : This will use in CONDITIONAL workflow execution.
                String clientResponse = nodeResponse.getClientResponse();

                List<Connection> childrenConnections = nodeNameToNodeMapping.get(nodeName).getConnections();

                if(childrenConnections == null) {
                    continue;
                }

                log.debug(String.format("Number of children of node : %s is %s", nodeName, childrenConnections.size()));

                for(Connection connection : childrenConnections) {

                    // TODO : This will use in CONDITIONAL workflow execution.
                    String edgeName = connection.getEdgeName();

                    String childNodeName = connection.getNodeName();

                    Boolean isNodeReadyToExecute = ExecuteWorkflowServiceHelper.isNodeReadyToExecute(childNodeName, nodeToParentNodesMapping, nodeNameToNodeMapping);

                    if (Boolean.TRUE.equals(isNodeReadyToExecute)) {
                        NodeConfiguration childNodeConfiguration = nodeNameToNodeConfigurationMapping.get(childNodeName);
                        ServerDetails childNodeServerDetails = teamNameToTeamConfigurationMapping.get(nodeNameToNodeMapping.get(childNodeName).getOwner()).getServerDetails();

                        Future<NodeResponse> childNodeResponse = executorService.submit(new NodeExecutor(childNodeConfiguration, childNodeServerDetails));
                        futureObjects.addLast(childNodeResponse);
                    } else {
                        // Nothing to do.
                    }

                }

            } catch(TimeoutException e) {
                log.info(String.format("Node failed because of timeOut. Pushing it again."));
                futureObjects.addLast(thisFutureObject);
            }
        }

        return;
    }
}
