package io.mosip.registration.liveness.regclient.demo;

import javafx.application.Application;

/**
 * Entry point. A main class that does not extend {@link Application} lets JavaFX run from the class path, as
 * the Registration Client's own launcher does.
 */
public final class RegClientDemo {

    private RegClientDemo() {
    }

    public static void main(String[] args) {
        Application.launch(RegClientDemoApp.class, args);
    }
}
