// importjob/consumer/ImportTaskConsumer.java
package com.wengu.backend.importjob.consumer;

import com.wengu.backend.config.RocketMQConfig;
import com.wengu.backend.importjob.service.ImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTaskConsumer implements InitializingBean, DisposableBean {

    private final ImportService importService;

    @Value("${wengu.rocketmq.namesrv}")
    private String namesrv;

    private DefaultMQPushConsumer consumer;

    @Override
    public void afterPropertiesSet() throws Exception {
        consumer = new DefaultMQPushConsumer("wengu-import-consumer");
        consumer.setNamesrvAddr(namesrv);
        consumer.subscribe(RocketMQConfig.TOPIC_IMPORT, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt msg : msgs) {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                String[] parts = body.split(":");
                if (parts.length != 2) { log.error("非法消息体 {}", body); continue; }
                Long taskId = Long.valueOf(parts[0]);
                Long userId = Long.valueOf(parts[1]);
                try {
                    importService.parseToDrafts(taskId, userId);
                } catch (Exception e) {
                    // ★ 不 RECONSUME_LATER：重投时 CAS 因 status=1 失败会跳过 → 任务卡死
                    log.error("解析异常（已置失败）taskId={}", taskId, e);
                    importService.markFailed(taskId, userId, "解析异常：" + e.getClass().getSimpleName());
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("导入消费者启动 topic={}", RocketMQConfig.TOPIC_IMPORT);
    }

    @Override
    public void destroy() {
        if (consumer != null) consumer.shutdown();
    }
}