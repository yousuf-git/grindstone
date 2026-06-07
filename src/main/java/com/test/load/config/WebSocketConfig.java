package com.test.load.config;

import com.test.load.websocket.StatsWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final StatsWebSocketHandler statsWebSocketHandler;

    public WebSocketConfig(StatsWebSocketHandler statsWebSocketHandler) {
        this.statsWebSocketHandler = statsWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(statsWebSocketHandler, "/ws/stats").setAllowedOrigins("*");
    }
}
