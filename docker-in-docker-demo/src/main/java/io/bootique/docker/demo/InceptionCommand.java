package io.bootique.docker.demo;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Ports;

import io.bootique.cli.Cli;
import io.bootique.command.CommandOutcome;
import io.bootique.command.CommandWithMetadata;
import io.bootique.docker.DockerClients;
import io.bootique.meta.application.CommandMetadata;

public class InceptionCommand extends CommandWithMetadata {

    private Provider<DockerClients> dockerClientsProvider;

    private static final String DOCKER_IN_DOCKER_IMAGE = "docker:dind-rootless";
    private static final String CONTAINER_NAME = "bootique-inception-demo";

    @Inject
    public InceptionCommand(Provider<DockerClients> dockerClientsProvider) {
        super(CommandMetadata.builder(
                InceptionCommand.class)
                .description("Starts Docker in Docker to demonstrate dual clients")
                .build());
        this.dockerClientsProvider = dockerClientsProvider;
    }

    @Override
    public CommandOutcome run(Cli cli) {

        // Even in case we have setup config file Docker client will try to build client
        // from environment (this is kinda default client)
        DockerClient client = dockerClientsProvider.get().getEnvClient();

        // Let's request info on default environment docker engine
        Info infoDefault = client.infoCmd().exec();
        System.out.println("[  default docker  ] ID: " + infoDefault.getId());
        System.out.println("[  default docker  ] OS: " + infoDefault.getOperatingSystem());
        System.out.println("[  default docker  ] Root dir: " + infoDefault.getDockerRootDir());

        // We assume that nobody use our selected name for their containers, but just to
        // be sure and to clean up any leftovers from previous runs, let's check and
        // remove container with same name
        List<Container> containers = client.listContainersCmd().withShowAll(true).exec();
        for (Container container : containers) {
            boolean match = false;
            for (String name : container.getNames()) {
                if (name.substring(1).equals(CONTAINER_NAME)) {
                    match = true;
                    break;
                }
            }
            if (match) {
                if (container.getState().equals("running")) {
                    client.killContainerCmd(container.getId()).exec();
                }
                client.removeContainerCmd(container.getId()).exec();
                client.waitContainerCmd(container.getId());
                break;
            }
        }

        ExposedPort containerPort = ExposedPort.tcp(2375);
        Ports portBindings = new Ports();
        portBindings.bind(containerPort, Ports.Binding.bindPort(9375));

        CreateContainerCmd createCommand = client.createContainerCmd(DOCKER_IN_DOCKER_IMAGE)
                .withName(CONTAINER_NAME)
                .withExposedPorts(containerPort)
                .withEnv("DOCKER_TLS_CERTDIR") // For demo purpose we are disabling TLS endpoint of
                                               // docker and gonna work with plain HTTP protocol
                .withCmd("--tls=false"); // We need to add argument for DinD container to mitigate
                                         // warning delay

        createCommand.getHostConfig() // Not all interesting for us configs can be set via command alone
                .withPortBindings(portBindings) // For example we need to set port forwarding to a particular port, must
                                                // do it in HostConfig
                .withPrivileged(true); // Also we need to run container in --privileged mode, due to DinD container
                                       // requirements (this helps with host system resource sharing under the hood)

        // Now we are ready to create container
        CreateContainerResponse container = createCommand.exec();

        // Container ready, but we need explicitly start it, due to API restriction on
        // atomicity of each change
        client.startContainerCmd(container.getId()).exec();

        // DinD image has a delay in start, we could add health checking, but for
        // current demo it'll be an overkill - so let's just sleep for 1 second (this
        // should be enough).
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Now DinD engine must be running already, so we can request same info fields
        // from there. As a result we gonna see that it's an independent Docker-engine
        // with independent client connected to it.
        DockerClient dockerInDockerClient = dockerClientsProvider.get().getClient("dind");
        Info infoDind = dockerInDockerClient.infoCmd().exec();
        System.out.println("[ docker in docker ] ID: " + infoDind.getId());
        System.out.println("[ docker in docker ] OS: " + infoDind.getOperatingSystem());
        System.out.println("[ docker in docker ] Root dir: " + infoDind.getDockerRootDir());

        // We'd like to clear environment after demo run, so we remove container now
        client.killContainerCmd(container.getId()).exec();
        client.removeContainerCmd(container.getId()).exec();
        client.waitContainerCmd(container.getId());

        return CommandOutcome.succeeded();
    }

}
