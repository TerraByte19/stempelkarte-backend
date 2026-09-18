package com.example.stemplekarte.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Newsletter-Versand hat einen eigenen Thread, damit ein Massenversand
 * nicht die Bestaetigungsmail blockiert, auf die ein Kunde gerade wartet.
 *
 * Fallstrick: Spring Boot legt seinen Standard-Pool nur an, wenn gar kein
 * Executor-Bean existiert. Faellt der applicationTaskExecutor weg, laufen
 * ALLE @Async-Aufrufe durch den einen Newsletter-Thread - nach aussen
 * unsichtbar, bis Mails minutenlang haengen.
 */
@SpringBootTest
class AsyncConfigTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void newsletterHatEigenenThreadNebenDemStandardPool() {
        assertThat(ctx.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(ctx.containsBean("newsletterExecutor")).isTrue();
        assertThat(ctx.getBean("applicationTaskExecutor"))
                .isNotSameAs(ctx.getBean("newsletterExecutor"));
    }
}
