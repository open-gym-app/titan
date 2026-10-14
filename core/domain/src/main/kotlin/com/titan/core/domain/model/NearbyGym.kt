package com.titan.core.domain.model

/** A [gym] and how far it is from where the user asked from, in kilometres. */
data class NearbyGym(
    val gym: Gym,
    val distanceKm: Double,
)
