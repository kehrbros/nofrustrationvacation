package com.nfv.recommendations.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Point in WGS84")
public record GeoPointDto(double latitude, double longitude) {
}