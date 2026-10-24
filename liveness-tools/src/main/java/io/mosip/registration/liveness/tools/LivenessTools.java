package io.mosip.registration.liveness.tools;

import java.util.Arrays;

/**
 * Entry point for the command-line tools.
 *
 * <pre>
 * evaluate &lt;dataset&gt; [options]   ISO/IEC 30107-3 PAD evaluation of the on-device engine
 * interop  [options]             device conformance + engine performance test
 * models   &lt;command&gt; ...         signed model packages: keygen, sign, verify, install, status, rollback
 * </pre>
 */
public final class LivenessTools {

    private LivenessTools() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        int status;
        switch (args[0]) {
            case "evaluate":
                status = PadEvaluationTool.run(rest);
                break;
            case "interop":
                status = InteropTestTool.run(rest);
                break;
            case "models":
                status = ModelPackageTool.run(rest);
                break;
            default:
                usage();
                status = 2;
        }
        System.exit(status);
    }

    private static void usage() {
        System.err.println("Usage: LivenessTools evaluate|interop|models ...\n"
                + "  evaluate <dataset> [--out DIR] [--passive-threshold 70] [--max-video-frames 60] [--min-attack-frames 2]\n"
                + "  interop  [--device webcam:0|sbi:HOST:PORT[:SUBID]|mock] [--seconds 5] [--min-fps 10]\n"
                + "           [--disconnect manual|auto] [--benchmark 200] [--out report.md] [--yes]\n"
                + "  models   keygen <private.pem> <public.pem>\n"
                + "           sign <dir> <private.pem> --name N --version V --sequence S [--min-engine 1.2.0]\n"
                + "           verify <dir> <public.pem> | install <dir> <repo> <public.pem>\n"
                + "           status <repo> <public.pem> | rollback <repo> <public.pem>");
    }

    /** Value of {@code --name value}, or {@code fallback}. */
    static String option(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
