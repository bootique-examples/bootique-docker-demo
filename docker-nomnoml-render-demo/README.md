# docker-nomnoml-render-demo

This example demonstrates one more realistic scenario of Bootique Docker module usage. Docker is not only awesome technology for developers, but also a good tool for environment management and integration tool.

In this demo project textual definition of diagram transferred into container, then binary isolated renderer initiated (also inside container), after diagram image representation is ready new container in place of rendering one is started carrying http server to transfer image in browser for user to admire.

## Prerequisites

- Java 11 or newer
- Apache Maven
- Docker Engine

## Build the Demo

Here is how to build it:

    $ git clone https://github.com/bootique-examples/bootique-docker-demo.git
    $ cd bootique-docker-demo/docker-nomnoml-render-demo
    $ mvn clean package

## Run the Demo

> Once again, as in previous examples you'll need running Docker Engine, please follow [official guide](https://docs.docker.com/config/daemon/#check-whether-docker-is-running) to check if daemon is running.

In this demo we'll use default (environment-aware) Docker client, so there will be no configuration file, but due to more complex setup there will be some options. To check them one can simple execute `jar` with an `-h` flag.

    $ java -jar target/docker-nomnoml-render-demo-2.0.RC1.jar -h

    NAME
        docker-nomnoml-render-demo-2.0.RC1.jar

    OPTIONS
        -b, --browser
            Flag signaling that application should open default browser to show use execution result

        -c yaml_location, --config=yaml_location
            Specifies YAML config location, which can be a file path or a URL.

        -f [val], --file[=val]
            Argument depicting file to be rendered by command

        -h, --help
            Prints this message.

        -H, --help-config
            Prints information about application modules and their configuration options.

        -k, --kill-container
            Flag signaling that target container must be killed after command execution

        -r, --restart-container
            Flag signaling that target container for command must be restarted before execution

So as we can see to run we'll need to supply some file with `--file[=val]` for renderer to work on. It's better to also add `--restart-container` and `--kill-container` flags - just to be on a safe side, this flags will guarantee that before and after execution no container name collision will occur. Also we can add `--browser` flag to navigate user to container URL (default browser will be selected in this case). Wrapping everything up one can run demo with this command:

    $ java -jar target/docker-nomnoml-render-demo-2.0.RC1.jar --file=./demo.nomnoml --browser

After rendering is finished and user's browser directed to image URL, application will continue to run. Press enter to stop and remove container. If you've force exited application with `Ctrl + C`, not giving it a chance to finish container work don't worry `--restart-container` flag will handle this case for you.

## Source code explanations

This example contains lot of preparation work, but to keep explanations concentrated on Docker Client, we'll skip most of it (also in source code these parts are extruded into utility files). To pass data between containers in this example we'll need some directory to mount it between.

```
File tempDir = Files.createTempDir();
tempDir.deleteOnExit();
```

And next we can construct and start container to transfer diagram source and render it then. As it can be seen logic of this operation is moved to method - this is done, because we'll need this method once again to spin up http server.

```
Container nomnomlContainer = ContainerUtils.getOrStart(client,
    NOMNOML_DOCKER_IMAGE,
    CONTAINER_NAME,
    new Bind(
        tempDir.getAbsolutePath(), // We'll mount temp dir to containers
        new Volume("/home/node/host") // This is an arbitrary we use for demo
    ),
    "bash"); // container will start in interactive mode with Bash running
```

We won't cover `getOrStart(...)` method logic, it's well documented in source (users are welcome to follow up there) - for now we just need to get that with this method call we'll get running container with bound directory from host and executing **bash** in interactive mode.

Next we need to attach to `stdin` of started container and transfer provided file with diagram definition (from _./demo.nomnoml_).

```
// try-with-resource must be used to prevent leaks and make execution more safe
try (BufferedReader fileReader = new BufferedReader(new FileReader(file));

    // To send commands to Bash we ought to user piped streams
    PipedOutputStream out = new PipedOutputStream();
    PipedInputStream pis = new PipedInputStream(out);
    OutputStreamWriter osw = new OutputStreamWriter(out);
    BufferedWriter writer = new BufferedWriter(osw);) {

    // We've already started container and now can attach to it with interactive
    // shell and execute commands inside container
    client.attachContainerCmd(nomnomlContainer.getId())
            .withStdIn(pis) // Here we attach instantiated before stream as stdin to container
            .withStdOut(true)
            .withFollowStream(true)
            .exec(new ResultCallback.Adapter<Frame>() {

                ...

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
```

After rendering finished container will exit, but it won't be removed automatically, one again thanks to atomicity principle laying as a foundation for Docker Engine API. So we need to remove it - this routine is also moved to utility method (by the way, pretty same code is used in previous demo)

```
ContainerUtils.removeByName(client, CONTAINER_NAME);
```

After path is clear, we can start Nginx server with image directory mounted as web-root in place (by name) of previous container. Unlike rendered container this one will need forwarded port to give user access to resulting image.

```
// This part is similar to docker-in-docker-demo
ExposedPort tcp80 = ExposedPort.tcp(80);
Ports portBindings = new Ports();
portBindings.bind(tcp80, Ports.Binding.empty());

// And this call is similar to previous container creation, but in place of command we pass port binding.
ContainerUtils.getOrStart(client,
    NGINX_DOCKER_IMAGE,
    CONTAINER_NAME,
    new Bind(tempDir.getAbsolutePath(), new Volume("/usr/share/nginx/html")),
    portBindings);
```

As it can be seen, we passed exposed ports, but not mapped port. This is done to overcome port collision issue, so Docker Engine will select free port for us. But to direct user's browser to correct URL, now we'll need to get mapped port for exposed one.

```
Integer mappedPort = ContainerUtils.getMappedPort(client, CONTAINER_NAME, tcp80);
```

Logic of `getMappedPort(...)` method is simple, get container published ports, iterate over received list and find one, which is bound to exposed port, then return. Finally tell user where is resulting image is, and if `--browser` option was passed to application direct browser to image URL.

```
try {
    URI nginxURL = new URI("http://localhost:" + mappedPort + "/output.png");
    System.out.println("Running nginx container in background, you can access render results via browser:");
    System.out.println(nginxURL.toString());
    DesktopUtils.handleBrowseURLOption(cli, nginxURL);
} catch (URISyntaxException e1) {
    e1.printStackTrace();
}
```
