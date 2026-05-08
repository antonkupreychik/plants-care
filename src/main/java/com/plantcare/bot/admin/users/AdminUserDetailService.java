package com.plantcare.bot.admin.users;

import com.plantcare.bot.admin.users.dto.UserDetailDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** 4 параллельных запроса: profile, plants, history, notifications. */
@Service
public class AdminUserDetailService {

    private final AdminUserDetailRepository repository;
    private final ExecutorService executor;

    public AdminUserDetailService(AdminUserDetailRepository repository,
                                  @Qualifier("adminQueryExecutor") ExecutorService executor) {
        this.repository = repository;
        this.executor = executor;
    }

    public Optional<UserDetailDto> loadDetail(long userId) {
        var profileOpt = repository.findProfile(userId);
        if (profileOpt.isEmpty()) return Optional.empty();
        var profile = profileOpt.get();

        var plantsF = CompletableFuture.supplyAsync(
                () -> repository.findPlants(userId), executor);
        var historyF = CompletableFuture.supplyAsync(
                () -> repository.findCareHistory(userId, 20), executor);
        var notifF = CompletableFuture.supplyAsync(
                () -> repository.findNotifications(userId, 20), executor);

        CompletableFuture.allOf(plantsF, historyF, notifF).join();

        return Optional.of(new UserDetailDto(
                profile.id(), profile.telegramChatId(), profile.username(), profile.timezone(),
                profile.quietHoursStart(), profile.quietHoursEnd(), profile.pausedUntil(),
                profile.conversationState(), profile.blocked(), profile.createdAt(),
                plantsF.join(), historyF.join(), notifF.join()
        ));
    }
}
