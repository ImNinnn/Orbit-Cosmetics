package dev.orbit.cosmetic.client.net;

/** One entry of the Orbit Cloud featured catalog: a cosmetic checked by hand and safe to use. */
public record FeaturedCosmetic(String id, String name, String sha256, int sizeBytes, String author, String description, int animations) {
}
