package io.bootique.docker.demo;

import javax.inject.Inject;
import javax.inject.Provider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;

import io.bootique.cli.Cli;
import io.bootique.command.CommandOutcome;
import io.bootique.command.CommandWithMetadata;
import io.bootique.meta.application.CommandMetadata;
import io.bootique.docker.DockerClients;

public class DefaultEngineInfoCommand extends CommandWithMetadata {

    private Provider<DockerClients> dockerClientsProvider;

    @Inject
    public DefaultEngineInfoCommand(Provider<DockerClients> dockerClientsProvider) {
        super(CommandMetadata.builder(
                DefaultEngineInfoCommand.class)
                .description("Connects to Docker-engine defined trough environment and prints info")
                .build());
        this.dockerClientsProvider = dockerClientsProvider;
    }

    @Override
    public CommandOutcome run(Cli cli) {

        DockerClient environmentClient = dockerClientsProvider.get().getEnvClient();
        Info info = environmentClient.infoCmd().exec();

        System.out.println("Architecture: " + info.getArchitecture());
        System.out.println("RAM Total: " + String.format("%.2fGB", info.getMemTotal() / 1073741824.0));
        System.out.println("CPU Cores Count: " + info.getNCPU());

        System.out.println("Docker Root: " + info.getDockerRootDir());
        System.out.println("Docker Storage Driver: " + info.getDriver());
        System.out.println("Docker Engine Version: " + info.getServerVersion());

        System.out.println("OS type: " + info.getOsType());
        System.out.println("OS name: " + info.getOperatingSystem());
        System.out.println("OS kernel version: " + info.getKernelVersion());

        return CommandOutcome.succeeded();
    }

}
