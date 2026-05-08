package com.plantcare.bot.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User findOrCreate(Long chatId, String username) {
        userRepository.insertOrIgnore(chatId, username);

        User user = userRepository.findByTelegramChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User must exist after insert-or-ignore"));

        return updateUsernameIfChanged(user, username);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByChatId(Long chatId) {
        return userRepository.findByTelegramChatId(chatId);
    }

    @Transactional
    public void updateState(User user, ConversationState newState) {
        log.info(
                "State transition for user {}: {} -> {}",
                user.getTelegramChatId(),
                user.getConversationState(),
                newState
        );

        user.setConversationState(newState);
        userRepository.save(user);
    }

    @Transactional
    public void setStateData(User user, String key, String value) {
        log.debug(
                "Setting state data for user {}: {} = {}",
                user.getTelegramChatId(),
                key,
                value
        );

        Map<String, Object> data = user.getStateData();

        if (data == null) {
            data = new HashMap<>();
        }

        data.put(key, value);

        user.setStateData(data);
        userRepository.save(user);
    }

    @Transactional
    public void removeStateData(User user, String key) {
        log.debug(
                "Removing state data for user {}: {}",
                user.getTelegramChatId(),
                key
        );

        Map<String, Object> data = user.getStateData();

        if (data == null) {
            return;
        }

        data.remove(key);

        user.setStateData(data);
        userRepository.save(user);
    }

    @Transactional
    public void resetToIdle(User user) {
        log.info(
                "Resetting user {} to IDLE. Cleaning up state data.",
                user.getTelegramChatId()
        );

        user.setConversationState(ConversationState.IDLE);

        if (user.getStateData() != null) {
            user.getStateData().clear();
        }

        userRepository.save(user);
    }

    private User updateUsernameIfChanged(User user, String newUsername) {
        if (newUsername != null && !newUsername.equals(user.getUsername())) {
            log.debug(
                    "Updating username for chatId={}: {} -> {}",
                    user.getTelegramChatId(),
                    user.getUsername(),
                    newUsername
            );

            user.setUsername(newUsername);
            userRepository.save(user);
        }

        return user;
    }
}