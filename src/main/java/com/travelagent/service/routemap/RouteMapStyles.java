package com.travelagent.service.routemap;

import java.util.Set;

public final class RouteMapStyles {

    public static final String ANIME = "anime_travel_map";
    public static final String JOURNAL = "journal_travel_map";
    public static final String WATERCOLOR = "watercolor_travel_map";
    private static final Set<String> SUPPORTED = Set.of(ANIME, JOURNAL, WATERCOLOR);

    private RouteMapStyles() {
    }

    public static String normalize(String style, String defaultStyle) {
        String candidate = style == null || style.isBlank() ? defaultStyle : style.trim();
        return SUPPORTED.contains(candidate) ? candidate : defaultStyle;
    }
}
