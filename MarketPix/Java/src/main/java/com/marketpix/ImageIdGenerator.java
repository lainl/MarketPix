package com.marketpix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Creates the id a finished set of images is stored under: day, time (24-hour hour and minute), month
 * and year, then a random letter and four random digits. 26 October 2026 at 13:40 gives
 * {@code 261340102026J2378}. The id is stored exactly like that, without dashes.
 */
@Component
public class ImageIdGenerator {

    public static final Pattern ID_PATTERN = Pattern.compile("\\d{12}[A-Z]\\d{4}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("ddHHmmMMyyyy");

    private final Random random = new SecureRandom();
    private final ZoneId zone;

    public ImageIdGenerator(@Value("${marketpix.id.timezone:}") String timezone) {
        this.zone = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
    }

    public String next() {
        return format(ZonedDateTime.now(zone), random);
    }

    static String format(ZonedDateTime time, Random random) {
        char letter = (char) ('A' + random.nextInt(26));
        return STAMP.format(time) + letter + String.format("%04d", random.nextInt(10_000));
    }
}
