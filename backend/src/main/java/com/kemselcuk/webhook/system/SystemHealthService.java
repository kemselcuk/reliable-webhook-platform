package com.kemselcuk.webhook.system;

import org.springframework.stereotype.Service;

@Service
public class SystemHealthService {

    SystemHealthResponse currentHealth() {
        return new SystemHealthResponse("UP", "reliable-webhook-platform");
    }
}
