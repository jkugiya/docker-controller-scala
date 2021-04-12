package com.github.j5ik2o.dockerController

import com.github.dockerjava.api.model.HostConfig.newHostConfig
import com.github.dockerjava.api.model.{ ExposedPort, Ports }
import com.github.j5ik2o.dockerController.NetworkSettingsImplicits._
import org.scalatest.freespec.AnyFreeSpec

import java.net.URL
import scala.concurrent.duration.Duration

abstract class DockerControllerSpecBase extends AnyFreeSpec with DockerControllerSpecSupport {

  val nginx: DockerController = DockerController(dockerClient)(
    imageName = "nginx",
    tag = Some("latest")
  ).configureCreateContainerCmd { cmd =>
    val hostPort: Int              = RandomPortUtil.temporaryServerPort()
    val containerPort: ExposedPort = ExposedPort.tcp(80)
    val portBinding: Ports         = new Ports()
    portBinding.bind(containerPort, Ports.Binding.bindPort(hostPort))
    logger.debug(s"hostPort = $hostPort, containerPort = $containerPort")
    cmd
      .withExposedPorts(containerPort)
      .withHostConfig(newHostConfig().withPortBindings(portBinding))
  }

  override val dockerControllers: Vector[DockerController] = {
    Vector(nginx)
  }

  override protected def startDockerContainer(
      dockerController: DockerController,
      testName: Option[String]
  ): DockerController = {
    require(dockerController == nginx)
    val result = super
      .startDockerContainer(dockerController, testName)
      .awaitCondition(Duration.Inf)(WaitPredicates.forLogMessageContained("Configuration complete; ready for start up"))
    Thread.sleep(1000)
    result
  }

  getClass.getSimpleName.stripPrefix("DockerController_").stripSuffix("_Spec") - {
    "run-1" in {
      val hostPort = nginx.inspectContainer().getNetworkSettings.bindingHostPort(ExposedPort.tcp(80)).get
      val url      = new URL(s"http://$dockerHost:$hostPort")
      HttpRequestUtil.wget(url)
    }
    "run-2" in {
      val hostPort = nginx.inspectContainer().getNetworkSettings.bindingHostPort(ExposedPort.tcp(80)).get
      val url      = new URL(s"http://$dockerHost:$hostPort")
      HttpRequestUtil.wget(url)
    }
  }
}