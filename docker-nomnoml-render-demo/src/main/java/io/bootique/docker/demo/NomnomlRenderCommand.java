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

package io.bootique.docker.demo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.URISyntaxException;

import javax.inject.Inject;
import javax.inject.Provider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.google.common.io.Files;

import io.bootique.cli.Cli;
import io.bootique.command.CommandOutcome;
import io.bootique.command.CommandWithMetadata;
import io.bootique.docker.DockerClients;
import io.bootique.docker.demo.util.ContainerUtils;
import io.bootique.docker.demo.util.DesktopUtils;
import io.bootique.meta.application.CommandMetadata;
import io.bootique.meta.application.OptionMetadata;

public class NomnomlRenderCommand extends CommandWithMetadata {

    private Provider<DockerClients> dockerClientsProvider;

    private static final String NOMNOML_DOCKER_IMAGE = "dapariscode/nomnoml-cli:latest";
    private static final String NGINX_DOCKER_IMAGE = "nginx:alpine";
    private static final String CONTAINER_NAME = "bootique-nomnoml-demo";
    private static final String NOMNOML_FILE_ARG = "file";

    @Inject
    public NomnomlRenderCommand(Provider<DockerClients> dockerClientsProvider) {
        super(commandMetadata());
        this.dockerClientsProvider = dockerClientsProvider;
    }

    private static CommandMetadata commandMetadata() {
        return CommandMetadata.builder(
                NomnomlRenderCommand.class)
                .description("Renders nomnoml file via Docker container")
                .addOption(renderFileOption())
                .addOption(ContainerUtils.restartContainerOption())
                .addOption(ContainerUtils.killContainerOption())
                .addOption(DesktopUtils.browseURLOption())
                .build();
    }

    public static OptionMetadata renderFileOption() {
        return OptionMetadata.builder(
                NOMNOML_FILE_ARG)
                .description("Argument depicting file to be rendered by command")
                .valueOptional()
                .build();
    }

    @Override
    public CommandOutcome run(Cli cli) {

        // Simple check of provided file existence using Bootique options
        String filePath = cli.optionString(NOMNOML_FILE_ARG);
        File file = new File(filePath);
        if (filePath == null) {
            return CommandOutcome.failed(-1, "No file to render was specified.");
        } else {
            if (!file.exists()) {
                return CommandOutcome.failed(-2, "No file '" + filePath + "' was found");
            }
        }

        // We'll need temp directory to output file
        File tempDir = Files.createTempDir();

        // Let's keep tmp clean after we are done with demo
        tempDir.deleteOnExit();

        // Resolving docker client module endpoint
        DockerClients dockerClients = dockerClientsProvider.get();

        // Here we instantiate client configured from the surrounding environment
        // properties as specified in the Docker Client
        DockerClient client = dockerClients.getEnvClient();

        // Stop and delete container with name used for demo, if -r flag was specified
        ContainerUtils.handleRestartOption(cli, client, CONTAINER_NAME);

        Container nomnomlContainer = ContainerUtils.getOrStart(client,
                NOMNOML_DOCKER_IMAGE,
                CONTAINER_NAME,
                new Bind(
                        tempDir.getAbsolutePath(), // We'll mount temp dir to containers
                        new Volume("/home/node/host")// This is an arbitrary we use for demo
                ),
                "bash"); // container will start in interactive mode with Bash running

        try (BufferedReader fileReader = new BufferedReader(new FileReader(file));
                PipedOutputStream out = new PipedOutputStream(); // To send commands to Bash we ought to user piped
                                                                 // streams
                PipedInputStream pis = new PipedInputStream(out);
                OutputStreamWriter osw = new OutputStreamWriter(out);
                BufferedWriter writer = new BufferedWriter(osw);) {

            // We've already started container and now can attach to it with interactive
            // shell and execute commands inside container
            client.attachContainerCmd(nomnomlContainer.getId())
                    .withStdIn(pis)
                    .withStdOut(true)
                    .withFollowStream(true)
                    .exec(new ResultCallback.Adapter<Frame>() {

                        @Override
                        public void onComplete() {
                            super.onComplete();
                            try {
                                writer.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onStart(Closeable stream) {
                            super.onStart(stream);
                            // After container started this block starts execution.
                            try {
                                // Here we creating file line by line inside container
                                String line = "";
                                while ((line = fileReader.readLine()) != null) {
                                    // It's a simple echo line to file call
                                    writer.write("echo '" + line + "' >> test.nomnoml\n");
                                }

                                // After file is ready we are finally can call nomnoml-cli and render file
                                writer.write("./nomnoml -i ./test.nomnoml -o /home/node/host/output.png\n");

                                // Execution is asynchronous, so we must stop container from inside container.
                                // We can achieve it via simple 'exit' from Bash - this will trigger container
                                // exit after execution of previous command.
                                writer.write("exit\n");

                                // Also due to nature of IO streams of container connection we need to flush
                                writer.flush();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }).awaitCompletion();
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
        // Above we've issued 'exit' command, so at this line container will already
        // stopped and shell will be detached (because of 'awaitCompletion()' call),
        // but now we need to remove container to reuse it name for viewer.
        ContainerUtils.removeByName(client, CONTAINER_NAME);

        ExposedPort tcp80 = ExposedPort.tcp(80);
        Ports portBindings = new Ports();
        portBindings.bind(tcp80, Ports.Binding.empty());

        // After nomnoml diagram image is ready we now can serve it via http, we'll use
        // Nginx for this
        ContainerUtils.getOrStart(client,
                NGINX_DOCKER_IMAGE,
                CONTAINER_NAME,
                new Bind(tempDir.getAbsolutePath(), new Volume("/usr/share/nginx/html")),
                portBindings);

        Integer mappedPort = ContainerUtils.getMappedPort(client, CONTAINER_NAME, tcp80);

        try {
            URI nginxURL = new URI("http://localhost:" + mappedPort + "/output.png");
            System.out.println("Running nginx container in background, you can access render results via browser:");
            System.out.println(nginxURL.toString());
            DesktopUtils.handleBrowseURLOption(cli, nginxURL);
        } catch (URISyntaxException e1) {
            e1.printStackTrace();
        }

        System.out.println("To stop demo - press ENTER key.");
        try {
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Stopping and removing Nginx container if particular flag is set
        ContainerUtils.handleKillOption(cli, client, CONTAINER_NAME);

        return CommandOutcome.succeeded();
    }

}
