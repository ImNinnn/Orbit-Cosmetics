package dev.orbit.cosmetic.common.anim;

/** The animated channels of one bone within one animation. Null channels are not animated. */
public record BoneTrack(int bone, Channel rotation, Channel position, Channel scale) {
}
