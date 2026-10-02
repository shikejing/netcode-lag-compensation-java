package com.github.shikejing.netcodelagcompensationjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.netcodelagcompensationjava.engine.ServerEngine;
import com.github.shikejing.netcodelagcompensationjava.journal.FileJournal;
import com.github.shikejing.netcodelagcompensationjava.journal.Journal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(NetcodeProperties.class)
public class NetcodeConfig {

    @Bean
    public Journal journal(NetcodeProperties properties, ObjectMapper mapper) {
        String path = properties.getJournalPath();
        if (path == null || path.isBlank()) return null;
        return new FileJournal(Path.of(path), mapper);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public ServerEngine serverEngine(NetcodeProperties properties, ObjectProvider<Journal> journalProvider) {
        return new ServerEngine(properties, journalProvider.getIfAvailable());
    }
}
