package com.apusic.admq.monitor.rabbitmq;

import lombok.Data;

@Data
public class MessageInfo {

    private long id;
    private long sendTime;
    private String data;
}
