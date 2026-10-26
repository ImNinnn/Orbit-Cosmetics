package dev.orbit.cosmetic.client.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** A slider over a numeric range, snapped to {@code step}. */
final class ValueSlider extends AbstractSliderButton {
	private final double min;
	private final double max;
	private final double step;
	private final DoubleFunction<Component> label;
	private final DoubleConsumer onChange;

	ValueSlider(int x, int y, int width, double min, double max, double step, double current, DoubleFunction<Component> label, DoubleConsumer onChange) {
		super(x, y, width, 20, Component.empty(), (Math.max(min, Math.min(max, current)) - min) / (max - min));
		this.min = min;
		this.max = max;
		this.step = step;
		this.label = label;
		this.onChange = onChange;
		updateMessage();
	}

	double current() {
		double raw = min + value * (max - min);
		double snapped = Math.round(raw / step) * step;
		return Math.max(min, Math.min(max, snapped));
	}

	@Override
	protected void updateMessage() {
		setMessage(label.apply(current()));
	}

	@Override
	protected void applyValue() {
		onChange.accept(current());
	}
}
