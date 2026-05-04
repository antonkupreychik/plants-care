package com.plantcare.bot.repository;

import com.plantcare.bot.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findAllByUserIdOrderByDisplayOrderAscNameAsc(Long userId);

    boolean existsByUserIdAndName(Long userId, String name);
}
