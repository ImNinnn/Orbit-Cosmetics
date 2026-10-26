package dev.orbit.cosmetic.common.anim.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A sandboxed subset of Bedrock Molang, enough for Blockbench keyframe expressions:
 * numbers, {@code query.*}/{@code q.*}, {@code variable.*}/{@code v.*}/{@code temp.*}/{@code t.*},
 * {@code math.*} functions, arithmetic, comparison, logic, {@code ?:}, {@code ??}, assignments,
 * {@code ;}-separated statements and {@code return}.
 *
 * <p>Unknown queries and variables evaluate to 0, as in Bedrock. Parsing enforces length, nesting and node
 * limits, and evaluation has no loops, so a malicious expression can only cost bounded work.
 */
public final class Molang {
	private static final int MAX_DEPTH = 48;
	private static final int MAX_NODES = 512;

	private final String source;
	private int pos;
	private int depth;
	private int nodes;

	private Molang(String source) {
		this.source = source;
	}

	public static MolangExpression parse(String source, int maxLength) throws MolangException {
		if (source == null) {
			return MolangExpression.constant(0);
		}
		if (source.length() > maxLength) {
			throw new MolangException("Expression longer than " + maxLength + " characters");
		}
		String trimmed = source.trim();
		if (trimmed.isEmpty()) {
			return MolangExpression.constant(0);
		}
		try {
			return MolangExpression.constant(Double.parseDouble(trimmed));
		} catch (NumberFormatException ignored) {
			// fall through to the full parser
		}
		Molang parser = new Molang(trimmed.toLowerCase(Locale.ROOT));
		return parser.parseProgram();
	}

	private MolangExpression parseProgram() throws MolangException {
		List<MolangExpression> statements = new ArrayList<>();
		boolean explicitReturn = false;
		int returnIndex = -1;
		while (true) {
			skipWhitespace();
			if (atEnd()) {
				break;
			}
			if (peekChar() == ';') {
				pos++;
				continue;
			}
			if (matchKeyword("return")) {
				statements.add(parseExpression());
				explicitReturn = true;
				returnIndex = statements.size() - 1;
				skipWhitespace();
				if (!atEnd() && peekChar() == ';') {
					pos++;
				}
				break;
			}
			statements.add(parseStatement());
			skipWhitespace();
			if (!atEnd()) {
				expect(';');
			}
		}
		if (statements.isEmpty()) {
			return MolangExpression.constant(0);
		}
		if (statements.size() == 1) {
			return statements.getFirst();
		}
		MolangExpression[] array = statements.toArray(new MolangExpression[0]);
		// Bedrock: a multi-statement program without return evaluates to 0. Blockbench returns the last value
		// for simple chains, so prefer the explicit return and otherwise the last statement.
		int resultIndex = explicitReturn ? returnIndex : array.length - 1;
		return context -> {
			double result = 0;
			for (int i = 0; i < array.length; i++) {
				double value = array[i].eval(context);
				if (i == resultIndex) {
					result = value;
				}
			}
			return result;
		};
	}

	private MolangExpression parseStatement() throws MolangException {
		int start = pos;
		String name = tryReadIdentifier();
		if (name != null) {
			String variable = variableName(name);
			skipWhitespace();
			if (variable != null && !atEnd() && peekChar() == '=' && (pos + 1 >= source.length() || source.charAt(pos + 1) != '=')) {
				pos++;
				MolangExpression value = parseExpression();
				count();
				return context -> {
					double result = value.eval(context);
					if (context.variables.size() < 64 || context.variables.containsKey(variable)) {
						context.variables.put(variable, result);
					}
					return result;
				};
			}
		}
		pos = start;
		return parseExpression();
	}

	private MolangExpression parseExpression() throws MolangException {
		enter();
		MolangExpression result = parseCoalesce();
		depth--;
		return result;
	}

	private MolangExpression parseCoalesce() throws MolangException {
		MolangExpression left = parseTernary();
		while (match("??")) {
			// Every value is defined in this sandbox, so ?? always yields the left side.
			parseTernary();
		}
		return left;
	}

	private MolangExpression parseTernary() throws MolangException {
		MolangExpression condition = parseOr();
		skipWhitespace();
		if (!atEnd() && peekChar() == '?' && !lookingAt("??")) {
			pos++;
			enter();
			MolangExpression whenTrue = parseTernary();
			MolangExpression whenFalse = MolangExpression.constant(0);
			skipWhitespace();
			if (!atEnd() && peekChar() == ':') {
				pos++;
				whenFalse = parseTernary();
			}
			depth--;
			MolangExpression t = whenTrue;
			MolangExpression f = whenFalse;
			return folded(context -> condition.eval(context) != 0 ? t.eval(context) : f.eval(context), condition, t, f);
		}
		return condition;
	}

	private MolangExpression parseOr() throws MolangException {
		MolangExpression left = parseAnd();
		while (match("||")) {
			MolangExpression l = left;
			MolangExpression r = parseAnd();
			left = folded(context -> (l.eval(context) != 0 || r.eval(context) != 0) ? 1 : 0, l, r);
		}
		return left;
	}

	private MolangExpression parseAnd() throws MolangException {
		MolangExpression left = parseEquality();
		while (match("&&")) {
			MolangExpression l = left;
			MolangExpression r = parseEquality();
			left = folded(context -> (l.eval(context) != 0 && r.eval(context) != 0) ? 1 : 0, l, r);
		}
		return left;
	}

	private MolangExpression parseEquality() throws MolangException {
		MolangExpression left = parseComparison();
		while (true) {
			MolangExpression l = left;
			if (match("==")) {
				MolangExpression r = parseComparison();
				left = folded(context -> l.eval(context) == r.eval(context) ? 1 : 0, l, r);
			} else if (match("!=")) {
				MolangExpression r = parseComparison();
				left = folded(context -> l.eval(context) != r.eval(context) ? 1 : 0, l, r);
			} else {
				return left;
			}
		}
	}

	private MolangExpression parseComparison() throws MolangException {
		MolangExpression left = parseAdditive();
		while (true) {
			MolangExpression l = left;
			if (match("<=")) {
				MolangExpression r = parseAdditive();
				left = folded(context -> l.eval(context) <= r.eval(context) ? 1 : 0, l, r);
			} else if (match(">=")) {
				MolangExpression r = parseAdditive();
				left = folded(context -> l.eval(context) >= r.eval(context) ? 1 : 0, l, r);
			} else if (match("<")) {
				MolangExpression r = parseAdditive();
				left = folded(context -> l.eval(context) < r.eval(context) ? 1 : 0, l, r);
			} else if (match(">")) {
				MolangExpression r = parseAdditive();
				left = folded(context -> l.eval(context) > r.eval(context) ? 1 : 0, l, r);
			} else {
				return left;
			}
		}
	}

	private MolangExpression parseAdditive() throws MolangException {
		MolangExpression left = parseMultiplicative();
		while (true) {
			MolangExpression l = left;
			if (match("+")) {
				MolangExpression r = parseMultiplicative();
				left = folded(context -> l.eval(context) + r.eval(context), l, r);
			} else if (match("-")) {
				MolangExpression r = parseMultiplicative();
				left = folded(context -> l.eval(context) - r.eval(context), l, r);
			} else {
				return left;
			}
		}
	}

	private MolangExpression parseMultiplicative() throws MolangException {
		MolangExpression left = parseUnary();
		while (true) {
			MolangExpression l = left;
			if (match("*")) {
				MolangExpression r = parseUnary();
				left = folded(context -> l.eval(context) * r.eval(context), l, r);
			} else if (match("/")) {
				MolangExpression r = parseUnary();
				left = folded(context -> {
					double divisor = r.eval(context);
					return divisor == 0 ? 0 : l.eval(context) / divisor;
				}, l, r);
			} else {
				return left;
			}
		}
	}

	private MolangExpression parseUnary() throws MolangException {
		if (match("-")) {
			enter();
			MolangExpression operand = parseUnary();
			depth--;
			return folded(context -> -operand.eval(context), operand);
		}
		if (match("!")) {
			enter();
			MolangExpression operand = parseUnary();
			depth--;
			return folded(context -> operand.eval(context) == 0 ? 1 : 0, operand);
		}
		if (match("+")) {
			return parseUnary();
		}
		return parsePrimary();
	}

	private MolangExpression parsePrimary() throws MolangException {
		count();
		skipWhitespace();
		if (atEnd()) {
			throw new MolangException("Unexpected end of expression");
		}
		char c = peekChar();
		if (c == '(') {
			pos++;
			MolangExpression inner = parseExpression();
			expect(')');
			return inner;
		}
		if (c == '{') {
			// Curly scopes are only meaningful with loops, which are unsupported; evaluate their contents.
			pos++;
			MolangExpression inner = parseExpression();
			skipWhitespace();
			while (!atEnd() && peekChar() == ';') {
				pos++;
				skipWhitespace();
			}
			expect('}');
			return inner;
		}
		if (Character.isDigit(c) || c == '.') {
			return MolangExpression.constant(readNumber());
		}
		if (c == '\'') {
			// Strings are not meaningful for transforms; treat them as 0 after skipping.
			int end = source.indexOf('\'', pos + 1);
			if (end < 0) {
				throw new MolangException("Unterminated string");
			}
			pos = end + 1;
			return MolangExpression.constant(0);
		}
		String identifier = tryReadIdentifier();
		if (identifier == null) {
			throw new MolangException("Unexpected character '" + c + "' at " + pos);
		}
		switch (identifier) {
			case "true":
				return MolangExpression.constant(1);
			case "false":
				return MolangExpression.constant(0);
			case "math.pi":
				return MolangExpression.constant(Math.PI);
			default:
				break;
		}
		skipWhitespace();
		if (!atEnd() && peekChar() == '(') {
			pos++;
			List<MolangExpression> args = new ArrayList<>();
			skipWhitespace();
			if (!atEnd() && peekChar() != ')') {
				do {
					args.add(parseExpression());
					skipWhitespace();
				} while (match(","));
			}
			expect(')');
			return function(identifier, args);
		}
		if (identifier.startsWith("query.") || identifier.startsWith("q.")) {
			String query = identifier.substring(identifier.indexOf('.') + 1);
			return context -> context.query(query);
		}
		String variable = variableName(identifier);
		if (variable != null) {
			return context -> context.variables.getOrDefault(variable, 0.0);
		}
		if (identifier.startsWith("math.")) {
			throw new MolangException("Unknown math constant " + identifier);
		}
		// context.*, geometry.*, material.* and similar are not used by transforms
		return MolangExpression.constant(0);
	}

	private MolangExpression function(String name, List<MolangExpression> args) throws MolangException {
		MolangExpression[] a = args.toArray(new MolangExpression[0]);
		if (name.startsWith("query.") || name.startsWith("q.")) {
			String query = name.substring(name.indexOf('.') + 1);
			return context -> context.query(query);
		}
		MolangExpression result = switch (name) {
			case "math.sin" -> unary(a, name, x -> Math.sin(Math.toRadians(x)));
			case "math.cos" -> unary(a, name, x -> Math.cos(Math.toRadians(x)));
			case "math.asin" -> unary(a, name, x -> Math.toDegrees(Math.asin(x)));
			case "math.acos" -> unary(a, name, x -> Math.toDegrees(Math.acos(x)));
			case "math.atan" -> unary(a, name, x -> Math.toDegrees(Math.atan(x)));
			case "math.abs" -> unary(a, name, Math::abs);
			case "math.floor" -> unary(a, name, Math::floor);
			case "math.ceil" -> unary(a, name, Math::ceil);
			case "math.round" -> unary(a, name, x -> (double) Math.round(x));
			case "math.trunc" -> unary(a, name, x -> x < 0 ? Math.ceil(x) : Math.floor(x));
			case "math.sqrt" -> unary(a, name, x -> x < 0 ? 0 : Math.sqrt(x));
			case "math.exp" -> unary(a, name, Math::exp);
			case "math.ln" -> unary(a, name, x -> x <= 0 ? 0 : Math.log(x));
			case "math.sign" -> unary(a, name, Math::signum);
			case "math.atan2" -> binary(a, name, (y, x) -> Math.toDegrees(Math.atan2(y, x)));
			case "math.pow" -> binary(a, name, Math::pow);
			case "math.mod" -> binary(a, name, (x, y) -> y == 0 ? 0 : x % y);
			case "math.min" -> binary(a, name, Math::min);
			case "math.max" -> binary(a, name, Math::max);
			case "math.clamp" -> {
				arity(a, name, 3);
				yield context -> Math.max(a[1].eval(context), Math.min(a[2].eval(context), a[0].eval(context)));
			}
			case "math.lerp" -> {
				arity(a, name, 3);
				yield context -> {
					double from = a[0].eval(context);
					return from + (a[1].eval(context) - from) * a[2].eval(context);
				};
			}
			case "math.lerprotate" -> {
				arity(a, name, 3);
				yield context -> {
					double from = a[0].eval(context);
					double delta = ((a[1].eval(context) - from) % 360 + 540) % 360 - 180;
					return from + delta * a[2].eval(context);
				};
			}
			case "math.hermite_blend" -> unary(a, name, t -> 3 * t * t - 2 * t * t * t);
			// Random functions would desynchronize clients, so they are deterministic midpoints here.
			case "math.random", "math.random_integer" -> {
				arity(a, name, 2);
				yield context -> (a[0].eval(context) + a[1].eval(context)) / 2;
			}
			case "math.die_roll", "math.die_roll_integer" -> {
				arity(a, name, 3);
				yield context -> a[0].eval(context) * (a[1].eval(context) + a[2].eval(context)) / 2;
			}
			default -> throw new MolangException("Unsupported function " + name);
		};
		return folded(result, a);
	}

	private static MolangExpression unary(MolangExpression[] a, String name, java.util.function.DoubleUnaryOperator op) throws MolangException {
		arity(a, name, 1);
		MolangExpression x = a[0];
		return context -> sanitize(op.applyAsDouble(x.eval(context)));
	}

	private static MolangExpression binary(MolangExpression[] a, String name, java.util.function.DoubleBinaryOperator op) throws MolangException {
		arity(a, name, 2);
		MolangExpression x = a[0];
		MolangExpression y = a[1];
		return context -> sanitize(op.applyAsDouble(x.eval(context), y.eval(context)));
	}

	private static void arity(MolangExpression[] a, String name, int expected) throws MolangException {
		if (a.length != expected) {
			throw new MolangException(name + " expects " + expected + " argument(s), got " + a.length);
		}
	}

	private static double sanitize(double value) {
		return Double.isFinite(value) ? value : 0;
	}

	/** Pre-evaluates an expression when all of its inputs are constant. */
	private static MolangExpression folded(MolangExpression expression, MolangExpression... inputs) {
		for (MolangExpression input : inputs) {
			if (!input.isConstant()) {
				return expression;
			}
		}
		return MolangExpression.constant(sanitize(expression.eval(new MolangContext())));
	}

	private static String variableName(String identifier) {
		for (String prefix : new String[] {"variable.", "v.", "temp.", "t."}) {
			if (identifier.startsWith(prefix) && identifier.length() > prefix.length()) {
				return identifier.substring(prefix.length());
			}
		}
		return null;
	}

	private void enter() throws MolangException {
		if (++depth > MAX_DEPTH) {
			throw new MolangException("Expression nested too deeply");
		}
	}

	private void count() throws MolangException {
		if (++nodes > MAX_NODES) {
			throw new MolangException("Expression too complex");
		}
	}

	private double readNumber() throws MolangException {
		int start = pos;
		while (!atEnd() && (Character.isDigit(peekChar()) || peekChar() == '.')) {
			pos++;
		}
		if (!atEnd() && (peekChar() == 'e') && pos + 1 < source.length()
			&& (Character.isDigit(source.charAt(pos + 1)) || source.charAt(pos + 1) == '-' || source.charAt(pos + 1) == '+')) {
			pos += 2;
			while (!atEnd() && Character.isDigit(peekChar())) {
				pos++;
			}
		}
		if (!atEnd() && peekChar() == 'f') {
			pos++; // tolerate C-style float suffixes
			try {
				return Double.parseDouble(source.substring(start, pos - 1));
			} catch (NumberFormatException e) {
				throw new MolangException("Invalid number at " + start);
			}
		}
		try {
			return Double.parseDouble(source.substring(start, pos));
		} catch (NumberFormatException e) {
			throw new MolangException("Invalid number at " + start);
		}
	}

	private String tryReadIdentifier() {
		skipWhitespace();
		int start = pos;
		while (!atEnd()) {
			char c = peekChar();
			if (Character.isLetterOrDigit(c) || c == '_' || (c == '.' && pos > start)) {
				pos++;
			} else {
				break;
			}
		}
		if (pos == start || !Character.isLetter(source.charAt(start)) && source.charAt(start) != '_') {
			pos = start;
			return null;
		}
		return source.substring(start, pos);
	}

	private boolean matchKeyword(String keyword) {
		skipWhitespace();
		if (source.startsWith(keyword, pos)) {
			int end = pos + keyword.length();
			if (end >= source.length() || !Character.isLetterOrDigit(source.charAt(end)) && source.charAt(end) != '_' && source.charAt(end) != '.') {
				pos = end;
				return true;
			}
		}
		return false;
	}

	private boolean match(String token) {
		skipWhitespace();
		if (source.startsWith(token, pos)) {
			// Do not split "==" into "=" or "<=" into "<"
			if (token.length() == 1 && pos + 1 < source.length()) {
				char next = source.charAt(pos + 1);
				char t = token.charAt(0);
				if ((t == '<' || t == '>' || t == '!') && next == '=') {
					return false;
				}
			}
			pos += token.length();
			return true;
		}
		return false;
	}

	private boolean lookingAt(String token) {
		return source.startsWith(token, pos);
	}

	private void expect(char c) throws MolangException {
		skipWhitespace();
		if (atEnd() || peekChar() != c) {
			throw new MolangException("Expected '" + c + "' at " + pos);
		}
		pos++;
	}

	private void skipWhitespace() {
		while (!atEnd() && Character.isWhitespace(peekChar())) {
			pos++;
		}
	}

	private boolean atEnd() {
		return pos >= source.length();
	}

	private char peekChar() {
		return source.charAt(pos);
	}
}
