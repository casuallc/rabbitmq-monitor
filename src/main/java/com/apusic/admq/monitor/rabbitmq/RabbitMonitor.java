package com.apusic.admq.monitor.rabbitmq;

import java.io.File;
import java.nio.file.Files;

public class RabbitMonitor {

    public static void main(String[] args) throws Exception {
        RabbitMonitor main = new RabbitMonitor();
        main.run(args);
    }

    void run(String[] args) throws Exception {
        if (args.length < 1) {
            throw new RuntimeException("Please config file path.");
        }

        File configFile = new File(args[0]);
        if (!Files.exists(configFile.toPath())) {
            throw new RuntimeException("Config file is not exists.");
        }

        MonitorTaskService service = new MonitorTaskService();
        service.start(configFile);
    }
}
