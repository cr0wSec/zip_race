package com.github.cr0wsec.ziprace.concurrent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;


@Configuration
public class WriteQueueConfig {

    /**
     * Bounded queue between upload threads and the writer thread.
     * {@link ArrayBlockingQueue} chosen for fixed-size memory layout and
     * single-lock simplicity, appropriate for a single-consumer pattern.
     *
     * @param capacity from {@code ziprace.queue.capacity}; once full,
     *                 {@code offer()} returns false to trigger HTTP 503 backpressure.
     */
    @Bean
    public BlockingQueue<WriteTask> writeQueue(
            @Value("${ziprace.queue.capacity}") int capacity) {
        return new ArrayBlockingQueue<>(capacity);
    }
}
