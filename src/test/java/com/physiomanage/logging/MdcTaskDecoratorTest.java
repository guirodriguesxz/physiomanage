package com.physiomanage.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        MDC.clear();
        pool.shutdownNow();
    }

    @Test
    void propagatesRequestMdcToWorkerThread() throws Exception {
        MDC.put("requestId", "req-123");
        MDC.put("clinicId", "clinic-a");
        AtomicReference<String> seenRequestId = new AtomicReference<>();
        AtomicReference<String> seenClinicId = new AtomicReference<>();

        pool.submit(decorator.decorate(() -> {
            seenRequestId.set(MDC.get("requestId"));
            seenClinicId.set(MDC.get("clinicId"));
        })).get();

        assertThat(seenRequestId.get()).isEqualTo("req-123");
        assertThat(seenClinicId.get()).isEqualTo("clinic-a");
    }

    @Test
    void doesNotLeakContextToNextTaskOnReusedThread() throws Exception {
        MDC.put("requestId", "req-123");
        pool.submit(decorator.decorate(() -> { })).get();

        // Tarefa seguinte na mesma thread, sem decorator: não pode herdar o requestId anterior
        Future<String> leaked = pool.submit(() -> MDC.get("requestId"));
        assertThat(leaked.get()).isNull();
    }

    @Test
    void worksWhenRequestHasNoMdc() throws Exception {
        MDC.clear();
        AtomicReference<String> seen = new AtomicReference<>("sentinel");

        pool.submit(decorator.decorate(() -> seen.set(MDC.get("requestId")))).get();

        assertThat(seen.get()).isNull();
    }
}
