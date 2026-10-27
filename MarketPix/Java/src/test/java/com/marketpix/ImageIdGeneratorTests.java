package com.marketpix;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Random;

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
    void generatedIdsMatchThePatternAndCarryNoDashes() {
        String id = new ImageIdGenerator("").next();

        assertTrue(ImageIdGenerator.ID_PATTERN.matcher(id).matches(), id);
    }
}
