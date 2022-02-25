# bootique-docker-demo

Examples of [bootique-docker](https://github.com/bootique/bootique-docker) module use:

- [docker-engine-info-demo](https://github.com/bootique-examples/bootique-docker-demo/tree/master/docker-engine-info-demo) - primitive demonstration of connection to Docker engine and requesting info about host (kind of `Hello World` example, but a good starting point to understand other examples)
- [docker-in-docker-demo](https://github.com/bootique-examples/bootique-docker-demo/tree/master/docker-in-docker-demo) - example showing how different clients can be configured and used simultaneously (two clients connecting to host engine and docker-in-docker engine respectively)
- [docker-nomnoml-render-demo](https://github.com/bootique-examples/bootique-docker-demo/tree/master/docker-nomnoml-render-demo) - advanced example introducing interactive usage of containers and simple orchestration done with Docker module (image containing platform-specific binary used to render an image from text description, followed with HTTP-server start to show user result from shared filesystem)
