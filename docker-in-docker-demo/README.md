# docker-in-docker-demo

[Christopher Nolan](https://en.wikipedia.org/wiki/Christopher_Nolan) inspired demo showing different ways to configure docker clients.

![Inception](https://upload.wikimedia.org/wikipedia/commons/thumb/3/3f/Humanium_spinning_top_by_Foreverspin.jpg/640px-Humanium_spinning_top_by_Foreverspin.jpg)

## Prerequisites

- Java 11 or newer
- Apache Maven
- Docker Engine

## Build the Demo

Here is how to build it:

    $ git clone https://github.com/bootique-examples/bootique-docker-demo.git
    $ cd bootique-docker-demo/docker-in-docker-demo
    $ mvn clean package

## Run the Demo

To play with this demo you'll need to have running Docker daemon. Please follow [official guide](https://docs.docker.com/config/daemon/#check-whether-docker-is-running) to check if daemon is running.

To keep thing simple we are not using any options for this application, but we need to give Bootique some `yaml` file with docker client configuration (we got you covered, simply use **config.yml** located at root of this project). Beware this demo can not be simply `java -jar`'ed and needs `-c file` or `--config=[file]` option, so command will look like:

    $ java -jar target/docker-in-docker-demo-2.0.RC1.jar -c ./config.yml

Running it will result in something like this:

    [  default docker  ] ID: 77FE:RQFL:6G4B:UZ43:AC6B:2B5T:ZSVP:7FGF:Y6QT:XLU4:TFPQ:OFEL
    [  default docker  ] OS: Arch Linux
    [  default docker  ] Root dir: /var/lib/docker
    [ docker in docker ] ID: 3KQI:IR6S:SKYN:BDFN:65PL:656A:Q4GO:QVI5:CXPA:YTCF:TM5O:BWMB
    [ docker in docker ] OS: Alpine Linux v3.15
    [ docker in docker ] Root dir: /home/rootless/.local/share/docker

Values will differ in respect with your configuration, but overall result should be similar. So what's happened? First application connects to default host Docker Engine (based on environment variables), after that container with Docker Engine image is started (docker-in-docker situation) and second client connects to this containerized Docker Engine, and finally both clients issue host info retrieval commands to their respective engines, printing partial results to stdout.

So what we see in the output means that there are actually two independent Docker Engines running, because client retrieved different IDs, OSes and even root directories.

## Source code explanations

Same is in [previous demo](../docker-engine-info-demo) we are constructing environment variables-based client and connect to default host Docker Engine. After that we construct and issue info retrieval command:

```
Info infoDefault = client.infoCmd().exec();
```

We do it for demonstration purpose, just to mark engine to which we are created. Now we need to go deeper, and instantiate containerized Docker Engine, but first let's check that there is no container with same name residing on host (and remove in case some leftovers exist).

```

List<Container> containers = client.listContainersCmd() // Creating list command
    .withShowAll(true) // We need to list all containers even exited or stopped
    .exec(); // And finally executing it

// Command execution results in a list of containers available on host, now we
// need to iterate over it and find container with target name (if exists)
for (Container container : containers) {

    boolean match = false;

    // Each container able to have multiple names (one public and some internal)
    for (String name : container.getNames()) {

        // Name always starts with '/' so to check name clearly we need to cut
        // one character from the begging of value
        if (name.substring(1).equals(CONTAINER_NAME)) {
            match = true;
            break;
        }
    }
    if (match) {

        // In case of running container we need to stop it first, due to
        // atomicity of changes assumed by Docker Engine
        if (container.getState().equals("running")) {
            client.killContainerCmd(container.getId()).exec();
        }

        // At this moment container is guaranteed to be stopped, and now we are
        // free to remove it from host.
        client.removeContainerCmd(container.getId()).exec();

        // Lastly we need to wait until container will not be in state of
        // command execution
        client.waitContainerCmd(container.getId());
        break;
    }
}
```

Docker Engine uses ports 2376 and 2375 for communication. We are interested in port 2375, due to it using plain tcp communication without any encryption (what is good for demonstration purposes only, by the way) - so we need to port forward it to host with mapping some other port not to overlap with default Docker Engine already running on host.

```
// Creating exposed container holder for port 2375
ExposedPort containerPort = ExposedPort.tcp(2375);

// Instantiating configuration holder for port binding
Ports portBindings = new Ports();

// Adding bind from container 2375 port to 9375 host port
portBindings.bind(containerPort, Ports.Binding.bindPort(9375));
```

And next we need to prepare create command and execute it:

```
CreateContainerCmd createCommand = client.createContainerCmd(DOCKER_IN_DOCKER_IMAGE)
        .withName(CONTAINER_NAME)
        .withExposedPorts(containerPort) // Setting port forwarding
        .withEnv("DOCKER_TLS_CERTDIR")   // Un-setting environment variable
        .withCmd("--tls=false");         // Adding argument flag to disable tls

// Not all docker client setting are accessible straight from create command,
// so we need to set port bindings for host config to make port accessible from
// host. Also docker-in-docker image must be ran with --privileged flag
createCommand.getHostConfig()
        .withPortBindings(portBindings)
        .withPrivileged(true);

// Finally after setup is done we can execute command, what will result in new
// container being created on host
CreateContainerResponse container = createCommand.exec();
```

As it being said before docker assumes single and atomic changes, so container will be created in non-running state, and to communicate with container we'll need to start it with additional start command as follows:

```
client.startContainerCmd(container.getId()).exec();
```

When container will be ready we'll be ready to construct client for started Docker Engine:

```
// Here we construct another docker client via named configuration 'dind'
DockerClient dockerInDockerClient = dockerClientsProvider.get().getClient("dind");

// Prepare and execute info command on docker-in-docker client
Info infoDind = dockerInDockerClient.infoCmd().exec();
```

Configuration is passed to Bootique via yaml file, for demonstration purposes we use simplified client setup.

    docker:
      clients:
        dind:
          type: noenv
          dockerHost: "tcp://localhost:9375"

This configuration defines only one named client setup (`dind`) which is of **noenv** type and sets dockerHost to **"tcp://localhost:9375"**. So instantiating this client will result in acquiring connection to Docker Engine running inside container, and executing info command will return values differing from host values proofing that there are two independent clients and engines running.

Docker module can support any number (not only two) of clients via different configurations defined in yaml file.

> There could be only one environment variables based client at once, but any number of configured clients.

For example in this demo we could add more docker-in-docker containers, but to keep things simple we restricted demo to one environment and one configuration-based clients.
