package dev.orbit.cosmetic.client.library;

import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.protocol.EquippedCosmetic;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Issue;
import dev.orbit.cosmetic.common.protocol.AssetId;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A .bbmodel file in the orbit folder. Mutated only on the client thread. */
public final class LocalCosmetic {
	public enum Status {
		/** Being read and validated in the background. */
		CHECKING,
		READY,
		INVALID
	}

	final Path path;
	/** Path relative to the orbit folder with '/' separators; the key used in orbit-state.json. */
	final String key;
	long modified;
	long size;
	Status status = Status.CHECKING;
	List<Issue> issues = List.of();
	CosmeticModel model;
	AssetId asset;
	byte[] bytes;
	boolean loaded;
	String duplicateOf;
	/** Why the current server refused this cosmetic, or null. */
	String serverRejection;
	/** Saved settings by animation name; survives edits of the model that reorder animations. */
	Map<String, AnimationSettings> savedSettings = Map.of();
	/** Saved physics by part key (see {@link #boneKey}); survives edits that reorder groups. */
	Map<String, List<AxisDrive>> savedPhysics = new LinkedHashMap<>();
	/** Contents of the settings file, kept so unknown keys survive rewriting. */
	JsonObject settingsRoot = new JsonObject();
	long settingsModified = -1;
	/** A same-named JSON exists that is not an Orbit settings file; it is never written. */
	boolean settingsForeign;
	String settingsProblem;
	String nameOverride;
	String author;
	String description;

	LocalCosmetic(Path path, String key) {
		this.path = path;
		this.key = key;
	}

	public Path path() {
		return path;
	}

	public String key() {
		return key;
	}

	public String displayName() {
		if (nameOverride != null) {
			return nameOverride;
		}
		if (model != null && !model.name().isBlank()) {
			return model.name();
		}
		String file = path.getFileName().toString();
		return file.endsWith(".bbmodel") ? file.substring(0, file.length() - ".bbmodel".length()) : file;
	}

	public Status status() {
		return status;
	}

	public List<Issue> issues() {
		if (settingsProblem == null) {
			return issues;
		}
		List<Issue> all = new ArrayList<>(issues);
		all.add(Issue.warning("settings_file", settingsProblem));
		return all;
	}

	/** The JSON file next to the model that holds this cosmetic's settings and details. */
	public Path settingsPath() {
		return CosmeticSettingsFile.pathFor(path);
	}

	public String author() {
		return author;
	}

	public String description() {
		return description;
	}

	public CosmeticModel model() {
		return model;
	}

	public AssetId asset() {
		return asset;
	}

	/** Canonical (texture-embedded) bytes; what gets uploaded and what {@link #asset()} hashes. */
	public byte[] bytes() {
		return bytes;
	}

	public boolean isLoaded() {
		return loaded;
	}

	public boolean isUsable() {
		return status == Status.READY && duplicateOf == null;
	}

	public String duplicateOf() {
		return duplicateOf;
	}

	public String serverRejection() {
		return serverRejection;
	}

	public boolean hasAnimations() {
		return model != null && model.hasAnimations();
	}

	/** Stable identity of a part: its Blockbench uuid, or its name for files without uuids. */
	public String boneKey(int bone) {
		if (model == null || bone < 0 || bone >= model.bones().size()) {
			return null;
		}
		Bone b = model.bones().get(bone);
		return b.uuid() != null ? b.uuid() : "name:" + b.name();
	}

	/** Physics links resolved against the current model; links to parts that no longer exist are skipped. */
	public List<PhysicsLink> physicsLinks() {
		if (model == null) {
			return List.of();
		}
		List<PhysicsLink> links = new ArrayList<>();
		for (Map.Entry<String, List<AxisDrive>> entry : savedPhysics.entrySet()) {
			for (Bone bone : model.bones()) {
				String key = bone.uuid() != null ? bone.uuid() : "name:" + bone.name();
				if (key.equals(entry.getKey())) {
					links.add(new PhysicsLink(bone.index(), entry.getValue()));
					break;
				}
			}
		}
		return links;
	}

	/** The axes configured for a part, or null if it has no physics. */
	public List<AxisDrive> physicsFor(int bone) {
		String key = boneKey(bone);
		return key == null ? null : savedPhysics.get(key);
	}

	/** What this cosmetic looks like as part of the equipped configuration sent to servers. */
	public EquippedCosmetic toEquipped() {
		return new EquippedCosmetic(asset, displayName(), animationSettings(), physicsLinks());
	}

	/** Settings for every animation of the model, in model order. */
	public List<AnimationSettings> animationSettings() {
		if (model == null) {
			return List.of();
		}
		List<AnimationSettings> result = new ArrayList<>(model.animations().size());
		for (AnimationClip clip : model.animations()) {
			AnimationSettings saved = savedSettings.get(clip.name());
			result.add(saved != null ? saved : AnimationSettings.defaultsFor(clip));
		}
		return result;
	}
}
