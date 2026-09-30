package io.rejar;

/**
 * Plain entry point: launching a non-{@code Application} class lets JavaFX run from the classpath (shaded jar).
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        RejarApp.main(args);
    }
}
