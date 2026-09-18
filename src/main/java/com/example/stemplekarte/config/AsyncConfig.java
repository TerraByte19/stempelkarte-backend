package com.example.stemplekarte.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * Standard-Pool fuer alles andere (@Async ohne Namen): Bestaetigungs-
     * und Lösch-Mails, Wallet-Pushes.
     *
     * Muss hier stehen, weil Spring Boot seinen eigenen applicationTaskExecutor
     * nur anlegt, solange gar kein Executor-Bean existiert - sobald unten der
     * Newsletter-Executor dazukommt, faellt der Standard-Pool sonst weg und
     * ALLE @Async-Aufrufe landen im Newsletter-Thread.
     */
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4);
        e.setMaxPoolSize(8);
        e.setQueueCapacity(200);
        e.setThreadNamePrefix("task-");
        return e;
    }

    /**
     * Eigener Thread NUR fuer den Newsletter-Versand.
     *
     * Ein Newsletter an ein paar hundert Kunden belegt seinen Thread
     * minutenlang. Im gemeinsamen Standard-Pool wuerden dahinter die
     * wichtigen Einzelmails warten - die Bestaetigungsmail, die ein Kunde
     * gerade JETZT braucht, um seine Karte aufs Handy zu bekommen.
     *
     * Genau ein Thread: zwei Newsletter laufen nacheinander statt den
     * Mailserver (Brevo) gleichzeitig zu beschiessen.
     */
    @Bean("newsletterExecutor")
    public TaskExecutor newsletterExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(1);
        e.setQueueCapacity(50);
        e.setThreadNamePrefix("newsletter-");
        // Beim Herunterfahren (Render-Deploy) den laufenden Versand noch
        // zu Ende bringen, damit der Verlauf nicht auf "wird versendet"
        // stehen bleibt.
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(60);
        return e;
    }
}
