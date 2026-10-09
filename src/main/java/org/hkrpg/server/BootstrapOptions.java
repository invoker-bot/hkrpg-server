package org.hkrpg.server;

public record BootstrapOptions(Mode mode, boolean noConsole, boolean check, boolean help, boolean noHandbook) {
    public enum Mode { BOTH, DISPATCH, GAME, DATABASE }

    public static BootstrapOptions parse(String[] args) {
        Mode mode = Mode.BOTH;
        boolean selected = false, noConsole = false, check = false, help = false, noHandbook = false;
        for (String arg : args) {
            Mode next = switch (arg) {
                case "--mode=both" -> Mode.BOTH;
                case "--mode=dispatch", "-dispatch" -> Mode.DISPATCH;
                case "--mode=game", "-game" -> Mode.GAME;
                case "--mode=database", "-database" -> Mode.DATABASE;
                default -> null;
            };
            if (next != null) {
                if (selected && next != mode) throw new IllegalArgumentException("Conflicting server modes");
                mode = next;
                selected = true;
                continue;
            }
            switch (arg) {
                case "--no-console" -> noConsole = true;
                case "--check" -> check = true;
                case "--help", "-h" -> help = true;
                case "--no-handbook", "-nohandbook", "-skiphandbook" -> noHandbook = true;
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        return new BootstrapOptions(mode, noConsole, check, help, noHandbook);
    }

    public String[] coreArguments() {
        var args = new java.util.ArrayList<String>();
        if (mode != Mode.BOTH) args.add("-" + mode.name().toLowerCase(java.util.Locale.ROOT));
        if (noConsole) args.add("--no-console");
        if (noHandbook) args.add("-nohandbook");
        return args.toArray(String[]::new);
    }
}
