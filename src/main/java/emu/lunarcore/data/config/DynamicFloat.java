package emu.lunarcore.data.config;

public class DynamicFloat {
    private boolean IsDynamic;
    private double FixedValue;

    /** Preserve the distinction between a literal and an unresolved postfix expression. */
    public java.util.OptionalDouble getConstantValue() {
        return IsDynamic ? java.util.OptionalDouble.empty() : java.util.OptionalDouble.of(FixedValue);
    }

    public double getValue() {
        if (!IsDynamic) {
            return FixedValue;
        }
        return 15;
    }
}
