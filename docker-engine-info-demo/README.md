# docker-engine-info-demo

Simplest example of Docker module use. This demo shows how to construct default (environment-based) client, connect to Docker engine and retrieve host info from engine API.

## Prerequisites

- Java 11 or newer
- Apache Maven
- Docker Engine

## Build the Demo

Here is how to build it:

    $ git clone https://github.com/bootique-examples/bootique-docker-demo.git
    $ cd bootique-docker-demo/docker-engine-info-demo
    $ mvn clean package

## Run the Demo

To execute the example you should have a Docker Engine running. How to check if daemon is running please follow [official guide](https://docs.docker.com/config/daemon/#check-whether-docker-is-running).

This demo is devoted to simplest possible module usage, so application has no options and to run we can simply run built artefact:

    $ java -jar target/docker-engine-info-demo-2.0.RC1.jar

Running it will result in something like this:

    Architecture: x86_64
    RAM Total: 31.30GB
    CPU Cores Count: 12
    Docker Root: /var/lib/docker
    Docker Storage Driver: overlay2
    Docker Engine Version: 20.10.12
    OS type: linux
    OS name: Arch Linux
    OS kernel version: 5.16.8-zen1-1-zen

Values will differ in respect with your configuration, but overall result should be similar.

## Source code explanations

Demo is packaged as a single command executing by default, and most of code will be a common boiler-plate needed in any case of some well documented Bootique-based application. We'll skip most of code and will concentrate only on module source here.

First of all we need to import needed classes (provided via module):

```
import io.bootique.docker.DockerClients; // Providing handle to construct clients
```

And also will need to imports for Java Docker Client:

```
import com.github.dockerjava.api.DockerClient; // Client for Docker Engine API
import com.github.dockerjava.api.model.Info; // Wrapper for Docker Engine 'info' response
```

Next using Bootique DI we may inject provider of DockerClients handle:

```
// Command class
public class DefaultEngineInfoCommand extends CommandWithMetadata {

    // Field to store handle
    private Provider<DockerClients> dockerClientsProvider;

    // Handle injection via constructor argument
    @Inject
    public DefaultEngineInfoCommand(Provider<DockerClients> dockerClientsProvider) {
        ...
        this.dockerClientsProvider = dockerClientsProvider;
    }
}
```

And finally we can retrieve client based on environment variables:

```
public class DefaultEngineInfoCommand extends CommandWithMetadata {

    @Override
    public CommandOutcome run(Cli cli) {
        // Getting DockerClients object via providers '.get()', and constructing
        // environment-based client
        DockerClient environmentClient = dockerClientsProvider.get().getEnvClient();
        ...
    }
}
```

When client is ready we can work with it as with common Docker Client object. For example we can issue info command.

    Info info = environmentClient.infoCmd().exec();

It's important to remember that Docker Client API uses statement prepare/statement execution patterns, so in assignment above Info object is used received as a result of `exec()` call, and first part `environmentClient.infoCmd()` is preparing command statement to be executed.
