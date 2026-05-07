package com.plantcare.bot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@Slf4j
public class AnalyticsService {

    public void track(Long userId, String eventName) {
        log.info("analytics_event={} userId={}", eventName, userId);
    }

    public void track(Long userId, String eventName, Map<String, Object> properties) {
        log.info("analytics_event={} userId={} properties={}", eventName, userId, properties);
    }
}