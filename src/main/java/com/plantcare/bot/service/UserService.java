package com.plantcare.bot.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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


    private User updateUsernameIfChanged(User user, String newUsername) {
        if (newUsername != null && !newUsername.equals(user.getUsername())) {
            log.debug("Updating username for chatId={}: {} -> {}",
                    user.getTelegramChatId(), user.getUsername(), newUsername);
            user.setUsername(newUsername);
        }
        return user;
    }
}