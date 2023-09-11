package com.apusic.admq.monitor.rabbitmq;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MonitorTaskService {

    private final List<MonitorTask> tasks = new ArrayList<>();
    private ScheduledExecutorService executorService;

    public void start(File configFile) throws Exception {
        executorService = Executors.newScheduledThreadPool(4);

        Properties props = new Properties();
        props.load(Files.newInputStream(configFile.toPath()));
        String service = props.getProperty("test.rabbit.service");
        String username = props.getProperty("test.rabbit.username");
        String password = props.getProperty("test.rabbit.password");
        int taskCount = Integer.parseInt(props.getProperty("test.rabbit.task.count"));

        log.info("Task loading ...");

        for (int i = 0; i < taskCount; i++) {
            TaskInfo taskInfo = new TaskInfo();
            taskInfo.setName(props.getProperty(String.format("test.rabbit.task.%s.name", i)));
            taskInfo.setVhost(props.getProperty(String.format("test.rabbit.task.%s.vhost", i)));
            taskInfo.setExchange(props.getProperty(String.format("test.rabbit.task.%s.exchange", i)));
            taskInfo.setQueue(props.getProperty(String.format("test.rabbit.task.%s.queue", i)));
            taskInfo.setRoutingKey(props.getProperty(String.format("test.rabbit.task.%s.routingkey", i)));
            taskInfo.setInterval(Integer.parseInt(props.getProperty(String.format("test.rabbit.task.%s.interval", i))));

            MonitorTask monitorTask = new MonitorTask(executorService, service, username, password, taskInfo);
            tasks.add(monitorTask);
            monitorTask.start();
        }

        executorService.scheduleWithFixedDelay(() -> {
            if (MonitorTask.failedCount.get() > 10) {
                log.info("Too many failed, exist now.");
                System.exit(-1);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
}
