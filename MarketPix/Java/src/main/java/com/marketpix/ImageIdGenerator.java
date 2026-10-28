package com.marketpix;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creates the id a finished set of images is stored under: day, time (24-hour hour and minute), month
 * and year, then a random letter and four random digits. 26 October 2026 at 13:40 gives
 * {@code 261340102026J2378}. The id is stored exactly like that, without dashes.
 *
 * <p>Ids are never repeated within a minute: a bulk job saves hundreds of items in quick succession, and
 * with 260,000 ids per minute a random repeat (which would overwrite a saved set) would otherwise become
 * likely. The ids handed out in the current minute are remembered and a repeat is drawn again.
 */
@Component
public class ImageIdGenerator {

    public static final Pattern ID_PATTERN = Pattern.compile("\\d{12}[A-Z]\\d{4}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("ddHHmmMMyyyy");
    private static final int IDS_PER_MINUTE = 26 * 10_000;

    private final Random random;
    private final Clock clock;
    private final Set<String> issuedThisMinute = new HashSet<>();
    private String currentMinute;

    @Autowired
    public ImageIdGenerator(@Value("${marketpix.id.timezone}") String timezone) {
        this(Clock.system(timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim())),
                new SecureRandom());
    }

    ImageIdGenerator(Clock clock, Random random) {
        this.clock = clock;
        this.random = random;
    }

    public synchronized String next() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        String minute = STAMP.format(now);
        if (!minute.equals(currentMinute)) {
            currentMinute = minute;
            issuedThisMinute.clear();
        }
        if (issuedThisMinute.size() >= IDS_PER_MINUTE) {
            throw new IllegalStateException("Every id for " + minute + " has been used.");
        }
        String id;
        do {
            id = format(now, random);
        } while (!issuedThisMinute.add(id));
        return id;
    }

    static String format(ZonedDateTime time, Random random) {
        char letter = (char) ('A' + random.nextInt(26));
        return STAMP.format(time) + letter + String.format("%04d", random.nextInt(10_000));
    }
}
