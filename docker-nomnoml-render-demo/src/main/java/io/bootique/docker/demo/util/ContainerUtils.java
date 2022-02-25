/*
 * Licensed to ObjectStyle LLC under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ObjectStyle LLC licenses
 * this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.bootique.docker.demo.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Ports;

import io.bootique.cli.Cli;
import io.bootique.meta.application.OptionMetadata;

public abstract class ContainerUtils {

    private static final String STATE_CREATED = "created";
    private static final String STATE_EXITED = "exited";
    private static final String STATE_RUNNING = "running";
    public static final String RESTART_CONTAINER_FLAG = "restart-container";
    public static final String KILL_CONTAINER_FLAG = "kill-container";

    public static OptionMetadata killContainerOption() {
        return OptionMetadata.builder(
                KILL_CONTAINER_FLAG)
                .description("Flag signaling that target container must be killed after command execution")
                .build();
    }

    public static void handleKillOption(Cli cli, DockerClient dockerClient, String containerName) {
        if (cli.hasOption(KILL_CONTAINER_FLAG)) {
            removeByName(dockerClient, containerName);
        }
    }

    public static OptionMetadata restartContainerOption() {
        return OptionMetadata.builder(RESTART_CONTAINER_FLAG)
                .description("Flag signaling that target container for command must be restarted before execution")
                .build();
    }

    public static void handleRestartOption(Cli cli, DockerClient dockerClient, String containerName) {
        if (cli.hasOption(RESTART_CONTAINER_FLAG)) {
            removeByName(dockerClient, containerName);
        }
    }

    // It's important to updated container handle every time, because Container
    // object does not receive state updated from Docker engine.
    public static Container getByName(DockerClient dockerClient, String containerName) {
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        for (Container container : containers) {
            for (String name : container.getNames()) {
                if (name.substring(1).equals(containerName)) {
                    return container;
                }
            }
        }
        return null;
    }

    public static boolean removeByName(DockerClient dockerClient, String containerName) {
        Container container = getByName(dockerClient, containerName);
        while (container != null) {
            try {
                switch (container.getState()) {
                    case STATE_EXITED:
                    case STATE_CREATED: {
                        dockerClient.removeContainerCmd(container.getId()).exec();
                        dockerClient.waitContainerCmd(container.getId());
                        return true;
                    }
                    case STATE_RUNNING: {
                        dockerClient.killContainerCmd(container.getId()).exec();
                        dockerClient.removeContainerCmd(container.getId()).exec();
                        dockerClient.waitContainerCmd(container.getId());
                        return true;
                    }
                }
            } catch (ConflictException e) {
                // In case of kill command execution on non-running container docker client will
                // throw exception, but due to state being momentary value we can not guarantee
                // actual state until container is fully destroyed and removed from engine
                // environment
            }
            container = getByName(dockerClient, containerName);
        }
        return true;
    }

    public static Container getOrStart(DockerClient dockerClient, String imageTag, String containerName,
            Bind bind, Ports ports) {
        return getOrStart(dockerClient, imageTag, containerName, bind, ports, null);
    }

    public static Container getOrStart(DockerClient dockerClient, String imageTag, String containerName,
            Bind bind, String containerCommand) {
        return getOrStart(dockerClient, imageTag, containerName, bind, null, containerCommand);
    }

    private static Container getOrStart(DockerClient dockerClient, String imageTag, String containerName,
            Bind bind, Ports ports, String containerCommand) {

        Container container = getByName(dockerClient, containerName);
        if (container == null) {

            boolean noImageOnHost = true;
            // If image is not present on host machine we'll get error on container create
            // attempt, so first we check if image is available on host
            List<Image> images = dockerClient.listImagesCmd().exec();
            for (Image image : images) {
                for (String tag : image.getRepoTags()) {
                    if (tag.equals(imageTag)) {
                        noImageOnHost = false;
                        break;
                    }
                }
                if (!noImageOnHost) {
                    break;
                }
            }

            // If there is no image found on host, we need to pull it from registry
            if (noImageOnHost) {
                try {
                    dockerClient.pullImageCmd(imageTag).exec(new PullImageResultCallback()).awaitCompletion();
                    noImageOnHost = false;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    return null;
                }
            }

            CreateContainerCmd createCmd = dockerClient.createContainerCmd(imageTag)
                    .withName(containerName)
                    .withStdinOpen(true)
                    .withTty(true);

            createCmd.getHostConfig().withBinds(Collections.singletonList(bind));

            // In case we'll need to override default command for container
            if (containerCommand != null) {
                createCmd.withCmd(Collections.singletonList(containerCommand));
            }

            // In case we'll need to expose some ports
            if (ports != null) {
                createCmd.withExposedPorts(new ArrayList<ExposedPort>(ports.getBindings().keySet()));
                createCmd.getHostConfig().withPortBindings(ports).withPublishAllPorts(true);
                // createCmd.getHostConfig().withPortBindings(ports);
            }

            // It's important to remember, that docker is not using create and start logic,
            // each step is separated, so we need no only to create container, but also wait
            // till it's created and than start it.
            dockerClient.startContainerCmd(createCmd.exec().getId()).exec();
            container = getByName(dockerClient, containerName);

        } else {
            switch (container.getState()) {
                case STATE_EXITED:
                case STATE_CREATED: {
                    dockerClient.startContainerCmd(container.getId()).exec();
                    break;
                }
            }
        }
        return container;
    }

    public static Integer getMappedPort(DockerClient dockerClient, String containerName, ExposedPort exposedPort) {
        Container container = getByName(dockerClient, containerName);
        if (container != null) {
            ContainerPort[] ports = container.getPorts();
            for (ContainerPort port : ports) {
                if (port.getPrivatePort().equals(exposedPort.getPort())
                        && !port.getIp().contains(":")) {
                    return port.getPublicPort();
                }
            }
        }
        return null;
    }
}
