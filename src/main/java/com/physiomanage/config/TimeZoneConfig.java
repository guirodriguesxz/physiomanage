package com.physiomanage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

/**
 * Fuso usado para interpretar datas "de calendário" da clínica (expediente,
 * dia da agenda, intervalos de relatório). Instants continuam persistidos em
 * UTC — só a conversão LocalDate/hora <-> Instant usa este fuso.
 */
@Configuration
public class TimeZoneConfig {

    @Bean
    public ZoneId clinicZone(@Value("${app.timezone}") String timezone) {
        return ZoneId.of(timezone);
    }
}
