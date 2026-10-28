package com.marketpix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Hands the {@code marketpix.imagemagick} setting to {@link ImageMagickConverter} when the app starts. */
@Component
public class ImageMagickSettings {

    public ImageMagickSettings(@Value("${marketpix.imagemagick}") String path) {
        ImageMagickConverter.configure(path);
    }
}
