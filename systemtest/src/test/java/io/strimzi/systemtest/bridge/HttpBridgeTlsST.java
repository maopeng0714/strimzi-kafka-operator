/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.bridge;

import io.fabric8.kubernetes.api.model.Service;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationTls;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.kafkaclients.externalClients.BasicExternalKafkaClient;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaBridgeUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.ServiceUtils;
import io.strimzi.systemtest.utils.specific.BridgeUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.crd.KafkaBridgeResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;

import java.util.Random;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.BRIDGE;
import static io.strimzi.systemtest.Constants.EXTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.bridge.HttpBridgeST.NAMESPACE;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag(BRIDGE)
@Tag(ACCEPTANCE)
@Tag(REGRESSION)
@Tag(NODEPORT_SUPPORTED)
@Tag(EXTERNAL_CLIENTS_USED)
@ExtendWith(VertxExtension.class)
class HttpBridgeTlsST extends HttpBridgeAbstractST {
    private static final Logger LOGGER = LogManager.getLogger(HttpBridgeTlsST.class);

    private String bridgeHost = "";
    private int bridgePort = Constants.HTTP_BRIDGE_DEFAULT_PORT;

    @Test
    void testSendSimpleMessageTls() throws Exception {
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();
        // Create topic
        KafkaTopicResource.topic(CLUSTER_NAME, topicName).done();

        JsonObject records = BridgeUtils.generateHttpMessages(MESSAGE_COUNT);
        JsonObject response = BridgeUtils.sendMessagesHttpRequest(records, bridgeHost, bridgePort, topicName, client);
        KafkaBridgeUtils.checkSendResponse(response, MESSAGE_COUNT);

        BasicExternalKafkaClient basicExternalKafkaClient = new BasicExternalKafkaClient.Builder()
            .withTopicName(topicName)
            .withNamespaceName(NAMESPACE)
            .withClusterName(CLUSTER_NAME)
            .withMessageCount(MESSAGE_COUNT)
            .withSecurityProtocol(SecurityProtocol.SSL)
            .withKafkaUsername(USER_NAME)
            .withConsumerGroupName(CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE))
            .build();

        assertThat(basicExternalKafkaClient.receiveMessagesTls(), is(MESSAGE_COUNT));
    }

    @Test
    void testReceiveSimpleMessageTls() throws Exception {
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();
        // Create topic
        KafkaTopicResource.topic(CLUSTER_NAME, topicName).done();
        KafkaTopicUtils.waitForKafkaTopicCreation(topicName);

        String groupId = "my-group-" + new Random().nextInt(Integer.MAX_VALUE);

        JsonObject config = new JsonObject();
        config.put("name", USER_NAME);
        config.put("format", "json");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Create consumer
        JsonObject response = BridgeUtils.createBridgeConsumer(config, bridgeHost, bridgePort, groupId, client);
        assertThat("Consumer wasn't created correctly", response.getString("instance_id"), is(USER_NAME));
        // Create topics json
        JsonArray topic = new JsonArray();
        topic.add(topicName);
        JsonObject topics = new JsonObject();
        topics.put("topics", topic);
        // Subscribe
        assertThat(BridgeUtils.subscribeHttpConsumer(topics, bridgeHost, bridgePort, groupId, USER_NAME, client), is(true));
        // Send messages to Kafka
        BasicExternalKafkaClient basicExternalKafkaClient = new BasicExternalKafkaClient.Builder()
            .withTopicName(topicName)
            .withNamespaceName(NAMESPACE)
            .withClusterName(CLUSTER_NAME)
            .withMessageCount(MESSAGE_COUNT)
            .withSecurityProtocol(SecurityProtocol.SSL)
            .withKafkaUsername(USER_NAME)
            .withConsumerGroupName(CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE))
            .build();


        assertThat(basicExternalKafkaClient.sendMessagesTls(), is(MESSAGE_COUNT));
        // Try to consume messages
        JsonArray bridgeResponse = BridgeUtils.receiveMessagesHttpRequest(bridgeHost, bridgePort, groupId, USER_NAME, client);
        if (bridgeResponse.size() == 0) {
            // Real consuming
            bridgeResponse = BridgeUtils.receiveMessagesHttpRequest(bridgeHost, bridgePort, groupId, USER_NAME, client);
        }
        assertThat("Sent message count is not equal with received message count", bridgeResponse.size(), is(MESSAGE_COUNT));
        // Delete consumer
        assertThat(BridgeUtils.deleteConsumer(bridgeHost, bridgePort, groupId, USER_NAME, client), is(true));
    }

    @Test
    void testTlsAuthWithWeirdNamedUser() throws Exception {
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();
        String groupId = "my-group-" + rng.nextInt(Integer.MAX_VALUE);

        // Create weird named user with . and maximum of 64 chars -> TLS
        String weirdUserName = "jjglmahyijoambryleyxjjglmahy.ijoambryleyxjjglmahyijoambryleyxasd";
        // Create user with normal name -> we don't need to set weird name for consumer
        String aliceUser = "alice";

        // Create topic
        KafkaTopicResource.topic(CLUSTER_NAME, topicName).done();
        // Create user
        KafkaUserResource.tlsUser(CLUSTER_NAME, weirdUserName).done();
        KafkaUserResource.tlsUser(CLUSTER_NAME, aliceUser).done();

        JsonObject config = new JsonObject();
        config.put("name", aliceUser);
        config.put("format", "json");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Create consumer
        JsonObject response = BridgeUtils.createBridgeConsumer(config, bridgeHost, bridgePort, groupId, client);
        assertThat("Consumer wasn't created correctly", response.getString("instance_id"), is(aliceUser));

        // Create topics json
        JsonArray topic = new JsonArray();
        topic.add(topicName);
        JsonObject topics = new JsonObject();
        topics.put("topics", topic);

        // Subscribe
        assertThat(BridgeUtils.subscribeHttpConsumer(topics, bridgeHost, bridgePort, groupId, aliceUser, client), is(true));

        BasicExternalKafkaClient basicExternalKafkaClient = new BasicExternalKafkaClient.Builder()
            .withTopicName(topicName)
            .withNamespaceName(NAMESPACE)
            .withClusterName(CLUSTER_NAME)
            .withMessageCount(MESSAGE_COUNT)
            .withSecurityProtocol(SecurityProtocol.SSL)
            .withKafkaUsername(weirdUserName)
            .withConsumerGroupName(CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE))
            .build();

        assertThat(basicExternalKafkaClient.sendMessagesTls(), is(MESSAGE_COUNT));
        // Try to consume messages
        JsonArray bridgeResponse = BridgeUtils.receiveMessagesHttpRequest(bridgeHost, bridgePort, groupId, aliceUser, client);
        if (bridgeResponse.size() == 0) {
            // Real consuming
            bridgeResponse = BridgeUtils.receiveMessagesHttpRequest(bridgeHost, bridgePort, groupId, aliceUser, client);
        }
        assertThat("Sent message count is not equal with received message count", bridgeResponse.size(), is(MESSAGE_COUNT));
        // Delete consumer
        assertThat(BridgeUtils.deleteConsumer(bridgeHost, bridgePort, groupId, aliceUser, client), is(true));
    }

    @BeforeAll
    void createClassResources() throws InterruptedException {
        LOGGER.info("Deploy Kafka and KafkaBridge before tests");

        // Deploy kafka
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 1, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewKafkaListenerExternalNodePort()
                            .withAuth(new KafkaListenerAuthenticationTls())
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        // Create Kafka user
        KafkaUserResource.tlsUser(CLUSTER_NAME, USER_NAME).done();

        // Initialize CertSecretSource with certificate and secret names for consumer
        CertSecretSource certSecret = new CertSecretSource();
        certSecret.setCertificate("ca.crt");
        certSecret.setSecretName(KafkaResources.clusterCaCertificateSecretName(CLUSTER_NAME));

        // Deploy http bridge
        KafkaBridgeResource.kafkaBridge(CLUSTER_NAME, KafkaResources.tlsBootstrapAddress(CLUSTER_NAME), 1)
            .editSpec()
                .withNewTls()
                    .withTrustedCertificates(certSecret)
                .endTls()
            .endSpec()
            .done();

        Service service = KafkaBridgeUtils.createBridgeNodePortService(CLUSTER_NAME, NAMESPACE, bridgeExternalService);
        KubernetesResource.createServiceResource(service, NAMESPACE).done();
        ServiceUtils.waitForNodePortService(bridgeExternalService);

        bridgePort = KafkaBridgeUtils.getBridgeNodePort(NAMESPACE, bridgeExternalService);
        bridgeHost = kubeClient(NAMESPACE).getNodeAddress();
    }
}
