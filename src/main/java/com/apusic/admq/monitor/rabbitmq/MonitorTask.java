package com.apusic.admq.monitor.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class MonitorTask {
    public static AtomicLong sequence = new AtomicLong();

    private final ScheduledExecutorService executorService;
    private final String service;
    private final String username;
    private final String password;
    private final TaskInfo info;
    private final ConcurrentLinkedQueue<MessageInfo> queue;

    private volatile boolean closed;
    private Connection connection;

    public MonitorTask(ScheduledExecutorService executorService, String service, String username,
                       String password, TaskInfo info) {
        this.executorService = executorService;
        this.service = service;
        this.username = username;
        this.password = password;
        this.info = info;
        this.queue = new ConcurrentLinkedQueue<>();
    }

    public void start() throws Exception {
        this.connection = getConnection();
        runTask();
        checkIfMessageExpired();

        log.info("[{}] Task start successful.", info.getName());
    }

    private void runTask() throws Exception {
        Channel channel = connection.createChannel();

        channel.basicConsume(info.getQueue(), false, new DefaultConsumer(channel) {

            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                                       byte[] body) throws IOException {
                channel.basicAck(envelope.getDeliveryTag(), false);

                long id = (long) properties.getHeaders().get("__admq__task_id");
                while (true) {
                    MessageInfo msgInfo = queue.peek();
                    if (msgInfo == null) {
                        log.error("[{}] 接收到未知消息，可能是上一次测试中发送的", info.getName());
                        break;
                    }
                    if (msgInfo.getId() == id) {
                        log.info("[{}] Message receive successful.", info.getName());
                        queue.poll();
                        break;
                    }
                    if (msgInfo.getId() < id) {
                        log.warn("[{}] 消息顺序异常", info.getName());
                        queue.poll();
                    }
                }
            }
        });

        // 发送消息
        executorService.execute(() -> sendMessage(channel));
    }

    private void sendMessage(Channel channel) {
        try {
            MessageInfo msg = new MessageInfo();
            msg.setId(sequence.incrementAndGet());
            msg.setSendTime(System.currentTimeMillis());
            msg.setData("apusic admq test message");

            AMQP.BasicProperties.Builder propBuilder = new AMQP.BasicProperties.Builder();
            propBuilder.headers(Collections.singletonMap("__admq__task_id", msg.getId()));
            channel.basicPublish(info.getExchange(), info.getRoutingKey(), propBuilder.build(), msg.getData().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[{}] 消息发送失败", info.getName(), e);
        }
        executorService.schedule(() -> sendMessage(channel), info.getInterval(), TimeUnit.SECONDS);
    }

    private Connection getConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setVirtualHost(info.getVhost());
        if (StringUtils.isNotBlank(this.username) && StringUtils.isNotBlank(this.password)) {
            factory.setUsername(this.username);
            factory.setPassword(this.password);
        }

        List<Address> addresses = new ArrayList<>();
        for (String url : service.split(",")) {
            String server = url.split(":")[0];
            int port = Integer.parseInt(url.split(":")[1]);

            Address address = new Address(server, port);
            addresses.add(address);
        }
        return factory.newConnection(addresses);
    }

    private void checkIfMessageExpired() {
        if (closed) {
            return;
        }

        try {
            MessageInfo msgInfo = queue.peek();
            if (msgInfo == null) {
                return;
            }

            // 超过三个周期没接收到消息则认为消息接收异常
            if (System.currentTimeMillis() - msgInfo.getSendTime() > 3 * info.getInterval()) {
                log.error("[{}] 长时间未接收到消息，请检查 MQ 服务是否正常", info.getName());
                queue.poll();
            }

        } catch (Exception e) {
            log.error("[{}] Internal error", info.getName(), e);
        }
        executorService.schedule(this::checkIfMessageExpired, 1, TimeUnit.SECONDS);
    }

    public void close() {
        closed = true;
    }
}
