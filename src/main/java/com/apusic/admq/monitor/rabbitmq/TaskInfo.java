package com.apusic.admq.monitor.rabbitmq;

import lombok.Data;

@Data
public class TaskInfo {

    private String name;
    private String vhost;
    private String exchange;
    private String queue;
    private String routingKey;
    private long interval;

}
