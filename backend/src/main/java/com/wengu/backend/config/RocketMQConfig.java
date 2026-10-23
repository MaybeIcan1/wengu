// config/RocketMQConfig.java
package com.wengu.backend.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RocketMQConfig {
    public static final String TOPIC_IMPORT = "wengu-import-task";

    @Value("${wengu.rocketmq.namesrv}")
    private String namesrv;

    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer importProducer() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("wengu-import-producer");
        producer.setNamesrvAddr(namesrv);
        producer.start();
        return producer;
    }
}