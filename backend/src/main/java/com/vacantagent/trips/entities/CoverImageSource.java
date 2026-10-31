package com.vacantagent.trips.entities;

/** Origin of a {@link TripCoverImage}. */
public enum CoverImageSource {
    /** Generated in the backend, e.g. by an image model from name and stops of the trip. */
    GENERATED,
    /** Uploaded by the user. */
    UPLOADED
}
