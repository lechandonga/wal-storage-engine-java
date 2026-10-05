package com.github.lechandonga.walstorageenginejava.kv.config;

import com.github.lechandonga.walstorageenginejava.kv.KeyValueStore;
import com.github.lechandonga.walstorageenginejava.kv.WalKeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KvStoreProperties.class)
public class KvStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KvStoreAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(KeyValueStore.class)
    public KeyValueStore keyValueStore(KvStoreProperties properties) {
        WalKeyValueStore store = new WalKeyValueStore(properties.getDirectory(), properties.getMaxLogBytes());
        log.info("键值存储已打开，目录={}，恢复结论: {}", properties.getDirectory(),
                store.getRecoveryReport().reason());
        return store;
    }
}
