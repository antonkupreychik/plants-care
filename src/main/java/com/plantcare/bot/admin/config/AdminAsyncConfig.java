package com.plantcare.bot.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Выделенный executor для параллельных запросов админки — не делим с прод-нагрузкой. */
@Configuration
public class AdminAsyncConfig {

    @Bean(name = "adminQueryExecutor", destroyMethod = "shutdown")
    public ExecutorService adminQueryExecutor(AdminProperties props) {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "admin-query-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(
                props.getDashboard().getQueryExecutorPoolSize(), threadFactory);
    }
}
