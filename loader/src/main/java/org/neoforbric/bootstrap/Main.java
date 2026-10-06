package org.neoforbric.bootstrap;

import org.neoforbric.loader.Failure;

/** No Minecraft, Knot or ModLauncher types belong on this entrypoint's classpath. */
public final class Main {
    private Main() {}
    public static void main(String[] args) {
        int status = execute(args);
        if (status != 0) System.exit(status);
    }
    public static int execute(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("NeoForbric Java 21 bootstrap prototype\n"
                    + "  --fixture --game <jar> --mods <directory> --main <class> [--side server|client] [--audit <json>] [-- <args>]\n"
                    + "  --inspect --mods <directory> [--audit <json>]\n"
                    + "Native metadata can be discovered; native runtime adapters are not implemented.");
            return 0;
        }
        try {
            LaunchOptions options = LaunchOptions.parse(args);
            new Bootstrap().run(options);
            System.out.println((options.inspect() ? "Inspection" : "Fixture launch") + " completed. Audit: " + options.audit().toAbsolutePath());
            return 0;
        } catch (Failure failed) {
            System.err.println("[" + failed.code() + "] " + failed.getMessage());
            return failed.code().equals("ARGUMENTS") ? 2 : 1;
        }
    }
}
