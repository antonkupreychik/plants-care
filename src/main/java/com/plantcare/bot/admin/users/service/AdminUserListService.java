package com.plantcare.bot.admin.users.service;

import com.plantcare.bot.admin.users.repository.AdminUserListRepository;
import com.plantcare.bot.admin.users.dto.UserListPageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminUserListService {

    private final AdminUserListRepository repository;

    public UserListPageDto find(String filter, String search, String sort, String direction, int page) {
        return repository.find(filter, search, sort, direction, page);
    }
}
