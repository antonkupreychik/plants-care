package com.plantcare.bot.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class UserServiceTest extends IntegrationTestBase {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    void createsNewUserWithDefaults() {
        User user = userService.findOrCreate(500L, "alice");

        assertThat(user.getId()).isNotNull();
        assertThat(user.getTelegramChatId()).isEqualTo(500L);
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getTimezone()).isEqualTo("UTC");
        assertThat(user.getConversationState()).isEqualTo(ConversationState.IDLE);
        assertThat(user.isBlocked()).isFalse();
    }

    @Test
    void allowsNullUsername() {
        User user = userService.findOrCreate(501L, null);
        assertThat(user.getUsername()).isNull();
    }

    @Test
    void returnsExistingUserOnSecondCall() {
        User first = userService.findOrCreate(502L, "bob");
        User second = userService.findOrCreate(502L, "bob");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void updatesUsernameIfChanged() {
        userService.findOrCreate(503L, "old_name");
        userService.findOrCreate(503L, "new_name");

        User reloaded = userRepository.findByTelegramChatId(503L).orElseThrow();
        assertThat(reloaded.getUsername()).isEqualTo("new_name");
    }

    @Test
    void doesNotOverwriteUsernameWithNull() {
        userService.findOrCreate(504L, "alice");
        userService.findOrCreate(504L, null);

        User reloaded = userRepository.findByTelegramChatId(504L).orElseThrow();
        assertThat(reloaded.getUsername()).isEqualTo("alice");
    }

    @Test
    void concurrentCallsForSameChatIdCreateOnlyOneUser() throws Exception {
        int threads = 10;
        Long chatId = 9999L;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            List<CompletableFuture<User>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            startGate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return userService.findOrCreate(chatId, "race_user");
                    }, executor))
                    .toList();

            startGate.countDown();

            List<User> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            Long firstId = results.get(0).getId();
            assertThat(results).allMatch(u -> u.getId().equals(firstId));

            assertThat(userRepository.findAll())
                    .filteredOn(u -> u.getTelegramChatId().equals(chatId))
                    .hasSize(1);
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}