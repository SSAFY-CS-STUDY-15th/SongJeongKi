package com.example.aotlab.monitoring;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final ApplicationContext applicationContext;

    public StartupLogger(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStartup(ApplicationReadyEvent event) {
        Duration startupTime = event.getTimeTaken();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        log.info(
                "AOT lab ready. pid={}, startupMs={}, beanDefinitions={}, heapUsedMb={}, heapCommittedMb={}, vm={}",
                ProcessHandle.current().pid(),
                startupTime != null ? startupTime.toMillis() : -1,
                this.applicationContext.getBeanDefinitionCount(),
                toMegabytes(heap.getUsed()),
                toMegabytes(heap.getCommitted()),
                ManagementFactory.getRuntimeMXBean().getVmName()
        );
    }

    private static long toMegabytes(long bytes) {
        return bytes / 1024 / 1024;
    }
}
