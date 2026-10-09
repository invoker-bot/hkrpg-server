package org.hkrpg.server.gameplay;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntToLongFunction;

/** Finite evaluator for all eighteen matched 4.6 opcodes, with explicit standard numeric inputs. */
public final class RogueResonanceExpression46 {
  private RogueResonanceExpression46() {}

  private interface Node {
    long value(IntToLongFunction inputs);
  }

  private record Constant(long raw) implements Node {
    public long value(IntToLongFunction inputs) {
      return standard(raw);
    }
  }

  private record Global(int key) implements Node {
    public long value(IntToLongFunction inputs) {
      if (RogueResonanceFunctions46.isMatchedFunction(key)) {
        throw new IllegalArgumentException("Unresolved numeric resonance variable");
      }
      return standard(inputs.applyAsLong(key));
    }
  }

  private record Add(Node left, Node right) implements Node {
    public long value(IntToLongFunction inputs) {
      return addStandard(left.value(inputs), right.value(inputs));
    }
  }

  private record Subtract(Node left, Node right) implements Node {
    public long value(IntToLongFunction inputs) {
      return subtractStandard(left.value(inputs), right.value(inputs));
    }
  }

  private record Multiply(Node left, Node right) implements Node {
    public long value(IntToLongFunction inputs) {
      return multiplyStandard(left.value(inputs), right.value(inputs));
    }
  }

  private record Negative(Node child) implements Node {
    public long value(IntToLongFunction inputs) {
      return numericResult(BigInteger.valueOf(child.value(inputs)).negate());
    }
  }

  private record Modulo(Node left, Node right) implements Node {
    public long value(IntToLongFunction inputs) {
      long a = left.value(inputs), b = right.value(inputs);
      return RogueResonanceFunctions46.invoke(RogueResonanceFunctions46.MOD_KEY, a, b);
    }
  }

  private record Compare(int op, Node left, Node right) implements Node {
    public long value(IntToLongFunction inputs) {
      long a = left.value(inputs), b = right.value(inputs);
      boolean near =
          BigInteger.valueOf(a)
                  .subtract(BigInteger.valueOf(b))
                  .abs()
                  .compareTo(BigInteger.valueOf(8588))
              < 0;
      boolean result =
          switch (op) {
            case 7 -> near;
            case 8 -> !near;
            case 9 -> !near && a < b;
            case 10 -> !near && a > b;
            case 11 -> a < b || near;
            case 12 -> a > b || near;
            default -> throw new IllegalArgumentException("Unmatched resonance comparison");
          };
      return result ? 1L << 33 : 0;
    }
  }

  private static boolean almostZero(long raw) {
    return raw > -8588 && raw < 8588;
  }

  private record Not(Node child) implements Node {
    public long value(IntToLongFunction inputs) {
      return almostZero(child.value(inputs)) ? 1L << 33 : 0;
    }
  }

  private record Ternary(Node condition, Node whenTrue, Node whenFalse) implements Node {
    public long value(IntToLongFunction inputs) {
      // The postfix VM computes both branches before it tests the condition.
      long c = condition.value(inputs), yes = whenTrue.value(inputs), no = whenFalse.value(inputs);
      return almostZero(c) ? no : yes;
    }
  }

  private record Divide(Node left, Node right) implements Node {
    public long value(IntToLongFunction inputs) {
      return divideStandard(left.value(inputs), right.value(inputs));
    }
  }

  private record Function(int key, List<Node> arguments) implements Node {
    private Function {
      arguments = List.copyOf(arguments);
    }

    public long value(IntToLongFunction inputs) {
      var values = new long[arguments.size()];
      for (int i = 0; i < values.length; i++) values[i] = arguments.get(i).value(inputs);
      return RogueResonanceFunctions46.invoke(key, values);
    }
  }

  private record Entry(Node node, int depth) {}

  public static final class Program {
    private final Node root;

    private Program(Node root) {
      this.root = root;
    }

    public long evaluateWithResolver(IntToLongFunction resolver) {
      if (resolver == null)
        throw new IllegalArgumentException("Missing resonance numeric resolver");
      return root.value(resolver);
    }

    /** Caller owns the meaning of each key; missing values never become zero. */
    public long evaluate(Map<Integer, Long> values) {
      if (values == null || values.size() > 255) {
        throw new IllegalArgumentException("Invalid resonance numeric context");
      }
      Map<Integer, Long> inputs;
      try {
        inputs = Map.copyOf(values);
      } catch (NullPointerException malformed) {
        throw new IllegalArgumentException("Invalid resonance numeric context entry", malformed);
      }
      if (inputs.size() > 255)
        throw new IllegalArgumentException("Invalid resonance numeric context");
      for (var entry : inputs.entrySet()) {
        standard(entry.getValue());
      }
      return root.value(
          key -> {
            Long raw = inputs.get(key);
            if (raw == null)
              throw new IllegalArgumentException("Unresolved numeric resonance variable");
            return raw;
          });
    }
  }

  private static long standard(long raw) {
    if ((raw & 1) != 0)
      throw new IllegalArgumentException("Extended arithmetic input is unmatched");
    return raw;
  }

  private static long numericResult(BigInteger raw) {
    if (raw.bitLength() > 63
        || raw.shiftRight(33).abs().compareTo(BigInteger.valueOf(0x40000000L)) >= 0) {
      throw new IllegalArgumentException("Extended arithmetic result is unmatched");
    }
    return standard(raw.longValueExact());
  }

  // Shared by the postfix VM and the native adsorption projection so quantization stays identical.
  static long addStandard(long a, long b) {
    standard(a);
    standard(b);
    return numericResult(BigInteger.valueOf(a).add(BigInteger.valueOf(b)));
  }

  static long subtractStandard(long a, long b) {
    standard(a);
    standard(b);
    return numericResult(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)));
  }

  static long multiplyStandard(long a, long b) {
    standard(a);
    standard(b);
    return numericResult(
        BigInteger.valueOf(a >> 1)
            .multiply(BigInteger.valueOf(b >> 1))
            .shiftRight(32)
            .shiftLeft(1));
  }

  static long divideStandard(long a, long b) {
    standard(a);
    standard(b);
    if (b == 0) throw new IllegalArgumentException("Resonance division by zero");
    var numerator = BigInteger.valueOf(a).shiftRight(1);
    var denominator = BigInteger.valueOf(b).shiftRight(1);
    var quotient = numerator.abs().shiftLeft(32).divideAndRemainder(denominator.abs());
    var magnitude = quotient[0];
    if (quotient[1].shiftLeft(1).compareTo(denominator.abs()) >= 0) {
      magnitude = magnitude.add(BigInteger.ONE);
    }
    var signed = numerator.signum() == denominator.signum() ? magnitude : magnitude.negate();
    return numericResult(signed.shiftLeft(1));
  }

  private static Entry pop(ArrayList<Entry> stack) {
    if (stack.isEmpty()) throw new IllegalArgumentException("Resonance expression stack underflow");
    return stack.removeLast();
  }

  /** Validate the entire finite program before reading any numeric context. */
  public static Program compile(byte[] bytecode, long[] constants, int[] variableKeys) {
    if (bytecode == null
        || bytecode.length < 1
        || bytecode.length > 255
        || constants == null
        || constants.length > 255
        || variableKeys == null
        || variableKeys.length > 255) {
      throw new IllegalArgumentException("Invalid resonance expression tables or program length");
    }
    byte[] code = bytecode.clone();
    long[] fixed = constants.clone();
    int[] keys = variableKeys.clone();
    var stack = new ArrayList<Entry>();
    int cursor = 0;
    while (cursor < code.length) {
      int op = Byte.toUnsignedInt(code[cursor++]);
      Node node;
      int depth = 1;
      if (op == 0 || op == 1) {
        if (cursor == code.length)
          throw new IllegalArgumentException("Truncated resonance table index");
        int index = Byte.toUnsignedInt(code[cursor++]);
        if (index >= (op == 0 ? fixed.length : keys.length)) {
          throw new IllegalArgumentException("Resonance table index out of range");
        }
        node = op == 0 ? new Constant(fixed[index]) : new Global(keys[index]);
      } else if (op >= 2 && op <= 12) {
        var right = pop(stack);
        var left = pop(stack);
        node =
            switch (op) {
              case 2 -> new Add(left.node(), right.node());
              case 3 -> new Subtract(left.node(), right.node());
              case 4 -> new Multiply(left.node(), right.node());
              case 5 -> new Divide(left.node(), right.node());
              case 6 -> new Modulo(left.node(), right.node());
              default -> new Compare(op, left.node(), right.node());
            };
        depth = Math.max(left.depth(), right.depth()) + 1;
      } else if (op == 13) {
        var no = pop(stack);
        var yes = pop(stack);
        var condition = pop(stack);
        node = new Ternary(condition.node(), yes.node(), no.node());
        depth = Math.max(condition.depth(), Math.max(yes.depth(), no.depth())) + 1;
      } else if (op == 14 || op == 15) {
        var child = pop(stack);
        node = op == 14 ? new Negative(child.node()) : new Not(child.node());
        depth = child.depth() + 1;
      } else if (op == 16) {
        if (cursor == code.length) {
          throw new IllegalArgumentException("Truncated resonance argument count");
        }
        int count = Byte.toUnsignedInt(code[cursor++]);
        if (count == 0 || stack.size() <= count) {
          throw new IllegalArgumentException("Unmatched resonance argument count");
        }
        var arguments = new ArrayList<Node>(count);
        int argumentDepth = 0;
        for (int i = 0; i < count; i++) {
          var argument = pop(stack);
          arguments.add(argument.node());
          argumentDepth = Math.max(argumentDepth, argument.depth());
        }
        java.util.Collections.reverse(arguments);
        var callee = pop(stack);
        if (!(callee.node() instanceof Global target)
            || !RogueResonanceFunctions46.acceptsArguments(target.key(), count)) {
          throw new IllegalArgumentException("Unmatched resonance call target or argument count");
        }
        node = new Function(target.key(), arguments);
        depth = Math.max(callee.depth(), argumentDepth) + 1;
      } else if (op == 17) {
        if (cursor != code.length || stack.size() != 1) {
          throw new IllegalArgumentException("Resonance return does not have one final value");
        }
        return new Program(stack.getFirst().node());
      } else {
        throw new IllegalArgumentException("Unmatched numeric resonance opcode");
      }
      if (depth > 32 || stack.size() >= 64) {
        throw new IllegalArgumentException("Resonance expression depth or stack limit exceeded");
      }
      stack.add(new Entry(node, depth));
    }
    throw new IllegalArgumentException("Resonance expression has no final return");
  }
}
