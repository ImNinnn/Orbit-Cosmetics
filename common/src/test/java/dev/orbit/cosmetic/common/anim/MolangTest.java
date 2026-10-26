package dev.orbit.cosmetic.common.anim;

import dev.orbit.cosmetic.common.anim.molang.Molang;
import dev.orbit.cosmetic.common.anim.molang.MolangContext;
import dev.orbit.cosmetic.common.anim.molang.MolangException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MolangTest {
	private static double eval(String source, MolangContext context) throws MolangException {
		return Molang.parse(source, 512).eval(context);
	}

	private static double eval(String source) throws MolangException {
		return eval(source, new MolangContext());
	}

	@Test
	void arithmeticAndPrecedence() throws Exception {
		assertEquals(7, eval("1 + 2 * 3"));
		assertEquals(9, eval("(1 + 2) * 3"));
		assertEquals(-4, eval("-2 * 2"));
		assertEquals(2.5, eval("5 / 2"));
		assertEquals(0, eval("1 / 0"), "division by zero is 0, never infinity");
		assertEquals(1, eval("2 > 1 && 1 >= 1"));
		assertEquals(0, eval("!1"));
		assertEquals(1, eval("1 == 1 || 0"));
		assertEquals(1, eval("3 != 2"));
		assertEquals(4, eval("1 > 2 ? 3 : 4"));
		assertEquals(3, eval("1 < 2 ? 3 : 4"));
		assertEquals(5, eval("5.0f"));
	}

	@Test
	void mathFunctionsUseDegrees() throws Exception {
		assertEquals(1, eval("math.sin(90)"), 1e-9);
		assertEquals(-1, eval("Math.Cos(180)"), 1e-9, "Molang is case-insensitive");
		assertEquals(3, eval("math.clamp(5, 0, 3)"));
		assertEquals(5, eval("math.lerp(0, 10, 0.5)"));
		assertEquals(Math.PI, eval("math.pi"), 1e-12);
		assertEquals(1, eval("math.mod(7, 3)"));
		assertEquals(8, eval("math.pow(2, 3)"));
		assertEquals(2, eval("math.abs(-2)"));
	}

	@Test
	void queriesAndVariables() throws Exception {
		MolangContext context = new MolangContext();
		context.animTime = 0.5;
		context.groundSpeed = 4;
		context.sneaking = true;
		assertEquals(0.5, eval("query.anim_time", context));
		assertEquals(10, eval("math.sin(q.anim_time * 180) * 10", context), 1e-9);
		assertEquals(4, eval("q.ground_speed", context));
		assertEquals(1, eval("q.is_sneaking", context));
		assertEquals(0, eval("q.unknown_query", context));
		assertEquals(6, eval("v.speed = q.ground_speed; return v.speed + 2;", context));
		assertEquals(3, eval("t.a = 1; t.b = 2; t.a + t.b", context));
	}

	@Test
	void constantsAreFolded() throws Exception {
		assertTrue(Molang.parse("1 + 2 * math.sin(90)", 512).isConstant());
		assertFalse(Molang.parse("q.anim_time * 2", 512).isConstant());
	}

	@Test
	void rejectsUnsafeOrInvalidInput() {
		assertThrows(MolangException.class, () -> Molang.parse("math.sin(", 512));
		assertThrows(MolangException.class, () -> Molang.parse("math.launch_missiles(1)", 512));
		assertThrows(MolangException.class, () -> Molang.parse("1 + 1", 3));
		assertThrows(MolangException.class, () -> Molang.parse("(".repeat(200) + "1" + ")".repeat(200), 1024));
		assertThrows(MolangException.class, () -> Molang.parse("1 +".repeat(600) + " 1", 4096));
		assertThrows(MolangException.class, () -> Molang.parse("@", 512));
	}
}
