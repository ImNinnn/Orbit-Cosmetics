package dev.orbit.cosmetic.common.anim.molang;

/** A compiled Molang expression. Evaluation never throws and never touches anything outside the context. */
@FunctionalInterface
public interface MolangExpression {
	double eval(MolangContext context);

	static MolangExpression constant(double value) {
		return new Constant(value);
	}

	/** @return true if the expression does not depend on the context, so it can be pre-evaluated */
	default boolean isConstant() {
		return false;
	}

	record Constant(double value) implements MolangExpression {
		@Override
		public double eval(MolangContext context) {
			return value;
		}

		@Override
		public boolean isConstant() {
			return true;
		}
	}
}
