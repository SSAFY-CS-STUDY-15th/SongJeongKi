package com.example.aotlab.monitoring;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

import com.example.aotlab.product.ProductService;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {

    private final ApplicationContext applicationContext;
    private final ProductService productService;

    public RuntimeController(ApplicationContext applicationContext, ProductService productService) {
        this.applicationContext = applicationContext;
        this.productService = productService;
    }

    @GetMapping
    RuntimeSnapshot snapshot() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new RuntimeSnapshot(
                ProcessHandle.current().pid(),
                ManagementFactory.getRuntimeMXBean().getVmName(),
                this.applicationContext.getBeanDefinitionCount(),
                this.productService.count(),
                toMegabytes(heap.getUsed()),
                toMegabytes(heap.getCommitted()),
                toMegabytes(heap.getMax())
        );
    }

    private static long toMegabytes(long bytes) {
        return bytes / 1024 / 1024;
    }

    record RuntimeSnapshot(
            long pid,
            String vmName,
            int beanDefinitionCount,
            int productCount,
            long heapUsedMb,
            long heapCommittedMb,
            long heapMaxMb
    ) {
    }
}
