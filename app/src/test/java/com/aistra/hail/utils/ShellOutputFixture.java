package com.aistra.hail.utils;

/** Child process used to exercise real pipe buffering, exit codes and timeouts. */
public final class ShellOutputFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "both":
                byte[] out = new byte[8192];
                byte[] err = new byte[8192];
                java.util.Arrays.fill(out, (byte) 'o');
                java.util.Arrays.fill(err, (byte) 'e');
                for (int i = 0; i < 128; i++) {
                    System.out.write(out);
                    System.err.write(err);
                }
                break;
            case "error":
                System.err.print("permission denied");
                System.exit(7);
                break;
            case "hang":
                Thread.sleep(60_000);
                break;
            case "input":
                System.out.write(System.in.readAllBytes());
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }
}
