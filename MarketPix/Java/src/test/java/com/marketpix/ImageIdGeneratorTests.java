package com.marketpix;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageIdGeneratorTests {

    @Test
    void formatsDayTimeMonthYearThenALetterAndFourDigits() {
        ZonedDateTime time = ZonedDateTime.of(2026, 10, 26, 13, 40, 0, 0, ZoneId.of("UTC"));

        String id = ImageIdGenerator.format(time, new Random(7));

        assertTrue(id.startsWith("261340102026"), id);
        assertTrue(ImageIdGenerator.ID_PATTERN.matcher(id).matches(), id);
        assertEquals(17, id.length());
    }

    @Test
    void neverRepeatsAnIdWithinAMinute() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-26T13:40:00Z"), ZoneId.of("UTC"));
        // Draws the same letter and digits twice before moving on, so the second id is a repeat.
        Random repeating = new Random() {
            private final int[] draws = {0, 1234, 0, 1234, 1, 1234};
            private int next;

            @Override
            public int nextInt(int bound) {
                return draws[next++ % draws.length];
            }
        };
        ImageIdGenerator generator = new ImageIdGenerator(clock, repeating);

        assertEquals("261340102026A1234", generator.next());
        assertEquals("261340102026B1234", generator.next());
    }

    @Test
    void aBulkJobsWorthOfIdsAreAllDifferent() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-26T13:40:00Z"), ZoneId.of("UTC"));
        ImageIdGenerator generator = new ImageIdGenerator(clock, new Random(1));
        Set<String> ids = new HashSet<>();

        for (int count = 0; count < 5_000; count++) {
            assertTrue(ids.add(generator.next()));
        }
    }

    @Test
    void generatedIdsMatchThePatternAndCarryNoDashes() {
        String id = new ImageIdGenerator("").next();

        assertTrue(ImageIdGenerator.ID_PATTERN.matcher(id).matches(), id);
    }
}
