package io.bootique.docker.demo.util;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

import io.bootique.cli.Cli;
import io.bootique.meta.application.OptionMetadata;

public abstract class DesktopUtils {

    public static final String INTERACTIVE_BROWSER_FLAG = "browser";

    public static OptionMetadata browseURLOption() {
        return OptionMetadata.builder(
                INTERACTIVE_BROWSER_FLAG)
                .description("Flag signaling that application should open default browser to show use execution result")
                .build();
    }

    public static void handleBrowseURLOption(Cli cli, URI uri) {
        if (cli.hasOption(INTERACTIVE_BROWSER_FLAG)) {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try {
                    Desktop.getDesktop().browse(uri);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
