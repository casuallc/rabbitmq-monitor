# rabbitmq-monitor

rabbitmq 服务状态探测程序，通过收发消息探测服务状态。
启动后会定时往交换机发送消息并记录到本地，同时启动消费者接收消息，如果超过指定时间没有收到消息则任务服务不正常。

- [x] 支持配置多个 MQ 服务地址
- [x] 支持添加多个探测任务
- [x] 支持设置探测时间间隔
- [x] 支持设置超时间隔
- [x] 支持检测消息顺序

# 使用方式

首先下载并构建软件包
```
git clone https://github.com/casuallc/rabbitmq-monitor.git
cd rabbitmq-monitor
mvn clean install -DskipTests
```

然后配置探测任务
```
# MQ 服务地址；多个地址用逗号隔开
test.rabbit.service=172.24.4.216:5672,172.24.4.217:5672
test.rabbit.username=
test.rabbit.password=

# 任务数量
test.rabbit.task.count=2
# 具体探测任务；下面的任务数要和上边定义的保持一致
test.rabbit.task.0.name=task0
test.rabbit.task.0.vhost=vhost03
test.rabbit.task.0.exchange=
test.rabbit.task.0.queue=qu01
test.rabbit.task.0.routingkey=key
# 测试时间间隔（秒）
test.rabbit.task.0.interval=5

test.rabbit.task.1.name=task1
test.rabbit.task.1.vhost=vhost03
test.rabbit.task.1.exchange=ex02
test.rabbit.task.1.queue=qu02
test.rabbit.task.1.routingkey=key
# 测试时间间隔（秒）
test.rabbit.task.1.interval=5
```

最后启动探测任务
```
cd bin
nohup ./admq-rabbit &
```
