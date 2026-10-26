package dev.orbit.cosmetic.common.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps the shipped example cosmetic valid. */
class ExampleModelTest {
	@Test
	void demoModelParsesWithEveryAnchor() throws Exception {
		Path demo = Path.of("..", "examples", "orbit_demo.bbmodel");
		BBModelParser.Result result = BBModelParser.parse(Files.readAllBytes(demo), "demo", ModelLimits.DEFAULT);
		assertTrue(result.ok(), result.issues().toString());
		assertTrue(result.issues().isEmpty(), result.issues().toString());
		CosmeticModel model = result.model();
		assertEquals(EnumSet.allOf(Anchor.class), EnumSet.copyOf(model.anchorsUsed()));
		assertEquals(3, model.animations().size());
		assertEquals(1, model.textures().size());
	}
}
