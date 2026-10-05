package com.github.lechandonga.walstorageenginejava.engine;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
@EnableConfigurationProperties(KvStoreProperties.class)
public class KvStoreConfiguration {

    private KeyValueStore store;

    @Bean
    public KeyValueStore keyValueStore(KvStoreProperties properties) {
        store = KeyValueStore.open(properties.toConfig());
        return store;
    }

    @PreDestroy
    public void shutdown() throws IOException {
        if (store != null) {
            store.close();
        }
    }
}
