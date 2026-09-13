package com.nexuspay.common.config;

import com.nexuspay.card.domain.CardTokenizer;
import com.nexuspay.common.time.BusinessCalendar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the Spring-free domain helpers into the container.
 * <p>
 * {@link BusinessCalendar} knows nothing about Spring; this class is the seam
 * that hands it its configuration. That is the whole layering rule in
 * miniature — the domain stays testable without a context, and the framework
 * knowledge lives out here.
 */
@Configuration
public class CommonConfig {

    @Bean
    public BusinessCalendar businessCalendar(NexusPayProperties properties) {
        return new BusinessCalendar(properties.clearing().cutOff());
    }

    @Bean
    public CardTokenizer cardTokenizer(NexusPayProperties properties) {
        return new CardTokenizer(properties.tokenization().secret());
    }

    /**
     * Injected rather than called statically, so time-dependent rules — card
     * expiry, authorization expiry, business-date cut-offs — can be tested at a
     * chosen instant instead of "whenever the suite happened to run".
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
