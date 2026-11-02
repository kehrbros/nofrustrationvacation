package com.nfv.trips.logic;

import com.nfv.assistant.logic.ImageGeneration;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Title photo drawn by the image model from name and stops of the trip. No transaction around the call to the model
 * (takes seconds), the image is stored in its own transaction afterwards.
 */
@Service
@RequiredArgsConstructor
public class CoverImageGeneration {
    private final TripLogic tripLogic;
    private final ImageGeneration imageGeneration;

    /**
     * Draws and stores a title photo unless the user has uploaded one meanwhile. {@code AssistantUnavailableException}
     * without a key or if the model fails, {@code AssistantRateLimitedException} over the user's limit.
     */
    public void generate(long appUserId, long tripId) {
        Trip trip = tripLogic.get(appUserId, tripId);
        List<Stop> stops = tripLogic.stops(appUserId, tripId);
        ImageGeneration.GeneratedImage image = imageGeneration.generate(appUserId, prompt(trip, stops));
        tripLogic.setGeneratedCoverImage(appUserId, tripId, image.contentType(), image.content());
    }

    static String prompt(Trip trip, List<Stop> stops) {
        StringBuilder prompt = new StringBuilder("Cover photo for a travel app, wide landscape format. Trip name: \"")
                .append(trip.getName()).append("\".");
        if (!stops.isEmpty()) {
            prompt.append(" Destinations: ")
                    .append(stops.stream().map(Stop::getName).collect(Collectors.joining(", ")))
                    .append('.');
        }
        return prompt.append(" Show a typical, inviting scene of the region or theme of the trip, photorealistic,"
                + " natural light. No text, letters, logos, borders or recognizable people.").toString();
    }
}
