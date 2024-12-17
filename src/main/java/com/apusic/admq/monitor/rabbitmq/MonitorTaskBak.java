package com.apusic.admq.monitor.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.AlreadyClosedException;
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
public class MonitorTaskBak {
    public AtomicLong sequence = new AtomicLong();
    public static AtomicLong failedCount = new AtomicLong();

    private final ScheduledExecutorService executorService;
    private final String service;
    private final String username;
    private final String password;
    private final TaskInfo info;
    private final ConcurrentLinkedQueue<MessageInfo> queue;

    private volatile boolean closed;
    private ConnectionFactory factory = new ConnectionFactory();
    private List<Address> addresses = new ArrayList<>();
    private Connection connection;
    private Channel sendChannel;
    private Channel receiveChannel;

    public MonitorTaskBak(ScheduledExecutorService executorService, String service, String username,
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
        this.sendChannel = connection.createChannel();
        this.receiveChannel = connection.createChannel();

        // 接收消息
        consumeMessage(receiveChannel);
        // 发送消息
        executorService.execute(() -> sendMessage(sendChannel));
        // 检测接收超时
        checkIfMessageExpired();

        log.info("[{}] Task start successful.", info.getName());
    }

    private void consumeMessage(Channel channel) throws Exception {
        if (receiveChannel == null) {
            return;
        }
        channel.basicConsume(info.getQueue(), false, new DefaultConsumer(channel) {

            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                                       byte[] body) throws IOException {
                channel.basicAck(envelope.getDeliveryTag(), false);

                long id = (long) properties.getHeaders().get("__admq__task_id");
                log.info("[{}] Receive message {} successful.", info.getName(), id);

                while (true) {
                    MessageInfo msgInfo = queue.peek();
                    if (msgInfo == null) {
                        log.info("[{}] 接收到未知消息，可能是上一次测试中发送的", info.getName());
                        break;
                    }
                    if (msgInfo.getId() > id) {
                        log.warn("[{}] 可能漏掉了消息, 消息ID：{}， 期待ID：{}", info.getName(), msgInfo.getId(), id);
                        break;
                    }
                    if (msgInfo.getId() == id) {
                        queue.poll();
                        break;
                    }
                    if (msgInfo.getId() < id) {
                        log.warn("[{}] 消息顺序异常, 消息ID：{}， 期待ID：{}", info.getName(), msgInfo.getId(), id);
                        queue.poll();
                    }
                }
            }
        });
        log.info("[{}] Create consumer successful.", info.getName());
    }

    private void sendMessage(Channel channel) {
        try {
            MessageInfo msg = new MessageInfo();
            msg.setId(sequence.incrementAndGet());
            msg.setSendTime(System.currentTimeMillis());
            msg.setData("apusic admq test message");
            queue.offer(msg);

            AMQP.BasicProperties.Builder propBuilder = new AMQP.BasicProperties.Builder();
            propBuilder.headers(Collections.singletonMap("__admq__task_id", msg.getId()));
            channel.basicPublish(info.getExchange(), info.getRoutingKey(), propBuilder.build(), msg.getData().getBytes(StandardCharsets.UTF_8));
            log.info("[{}] Send message {} successful.", info.getName(), msg.getId());
        } catch (AlreadyClosedException e) {
            if (e.getMessage().contains("connection is already closed")) {
                reCreateConnection();
            } else {
                sendChannel = reCreateChannel(sendChannel);
            }
            log.error("[{}] 消息发送失败", info.getName(), e);
            failedCount.incrementAndGet();
        } catch (Throwable e) {
            log.error("[{}] 消息发送失败", info.getName(), e);
            failedCount.incrementAndGet();
        }
        executorService.schedule(() -> sendMessage(channel), info.getInterval(), TimeUnit.SECONDS);
    }

    private Connection getConnection() throws Exception {
        factory.setVirtualHost(info.getVhost());
        factory.setAutomaticRecoveryEnabled(true);

        if (StringUtils.isNotBlank(this.username) && StringUtils.isNotBlank(this.password)) {
            factory.setUsername(this.username);
            factory.setPassword(this.password);
        }

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
                executorService.schedule(this::checkIfMessageExpired, 1, TimeUnit.SECONDS);
                return;
            }

            // 超过三个周期没接收到消息则认为消息接收异常
            if (System.currentTimeMillis() - msgInfo.getSendTime() > 3 * info.getInterval() * 1000) {
                log.error("[{}] 长时间未接收到消息，请检查 MQ 服务是否正常", info.getName());
                queue.poll();
                receiveChannel = reCreateChannel(receiveChannel);
                consumeMessage(receiveChannel);
                failedCount.incrementAndGet();
            }

        } catch (Exception e) {
            log.error("[{}] Internal error", info.getName(), e);
            failedCount.incrementAndGet();
        }
        executorService.schedule(this::checkIfMessageExpired, 1, TimeUnit.SECONDS);
    }

    public void close() {
        closed = true;
    }

    private synchronized void reCreateConnection() {
        if (connection.isOpen()) {
            return;
        }
        log.warn("[{}] Re create connection", info.getName());
        try {
            try {
                connection.close();
            } catch (Throwable ignore) {
            }
            connection = factory.newConnection(addresses);
            sendChannel = reCreateChannel(sendChannel);
            receiveChannel = reCreateChannel(receiveChannel);
        } catch (Throwable ignore) {
        }
    }

    private synchronized Channel reCreateChannel(Channel channel) {
        if (channel.isOpen()) {
            return channel;
        }

        log.warn("[{}] Re create channel", info.getName());
        if (channel == null) {
            return null;
        }
        try {
            try {
                channel.close();
            } catch (Throwable ignore) {
            }
            return connection.createChannel();
        } catch (Throwable ignore) {
        }
        return null;
    }
}
