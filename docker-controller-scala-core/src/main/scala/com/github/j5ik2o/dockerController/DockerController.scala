package com.github.j5ik2o.dockerController

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command._
import com.github.dockerjava.api.model.{ Frame, Image, PullResponseItem }
import me.tongfei.progressbar.{ DelegatingProgressBarConsumer, ProgressBar, ProgressBarBuilder, ProgressBarStyle }
import org.slf4j.{ Logger, LoggerFactory }

import java.lang
import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit }
import java.util.{ Timer, TimerTask }
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration }
import scala.jdk.CollectionConverters._

case class CmdConfigures(
    createContainerCmdConfigure: CreateContainerCmd => CreateContainerCmd = identity,
    removeContainerCmdConfigure: RemoveContainerCmd => RemoveContainerCmd = identity,
    startContainerCmdConfigure: StartContainerCmd => StartContainerCmd = identity,
    stopContainerCmdConfigure: StopContainerCmd => StopContainerCmd = identity,
    inspectContainerCmdConfigure: InspectContainerCmd => InspectContainerCmd = identity,
    listImageCmdConfigure: ListImagesCmd => ListImagesCmd = identity,
    pullImageCmdConfigure: PullImageCmd => PullImageCmd = identity
)

trait DockerController {
  def containerId: String
  def dockerClient: DockerClient
  def imageName: String
  def tag: Option[String]
  def cmdConfigures: Option[CmdConfigures]
  def configureCmds(cmdConfigures: CmdConfigures): DockerController

  def configureCreateContainerCmd(f: CreateContainerCmd => CreateContainerCmd = identity): DockerController = {
    val newCmdConfigures = cmdConfigures match {
      case Some(cc) =>
        cc.copy(createContainerCmdConfigure = f)
      case None =>
        CmdConfigures(createContainerCmdConfigure = f)
    }
    configureCmds(newCmdConfigures)
  }

  def createContainer(f: CreateContainerCmd => CreateContainerCmd = identity): DockerController
  def removeContainer(f: RemoveContainerCmd => RemoveContainerCmd = identity): DockerController
  def startContainer(f: StartContainerCmd => StartContainerCmd = identity): DockerController
  def stopContainer(f: StopContainerCmd => StopContainerCmd = identity): DockerController
  def inspectContainer(f: InspectContainerCmd => InspectContainerCmd = identity): InspectContainerResponse
  def listImages(f: ListImagesCmd => ListImagesCmd = identity): Vector[Image]
  def existsImage(p: Image => Boolean): Boolean
  def pullImageIfNotExists(f: PullImageCmd => PullImageCmd = identity): DockerController
  def pullImage(f: PullImageCmd => PullImageCmd = identity): DockerController
  def awaitCondition(duration: Duration)(predicate: Frame => Boolean): DockerController
}

object DockerController {

  def apply(dockerClient: DockerClient, outputFrameInterval: FiniteDuration = 500.millis)(
      imageName: String,
      tag: Option[String] = None
  ): DockerController = new DockerControllerImpl(dockerClient, outputFrameInterval)(imageName, tag)
}

private[dockerController] class DockerControllerImpl(
    val dockerClient: DockerClient,
    outputFrameInterval: FiniteDuration = 500.millis
)(
    val imageName: String,
    val tag: Option[String] = None
) extends DockerController {

  protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private var _containerId: String = _

  private def repoTag: String = tag.fold(imageName)(t => s"$imageName:$t")

  override def containerId: String = _containerId

  private var _cmdConfigures: Option[CmdConfigures] = None

  private final val MaxProgressBarLength = 120

  private val progressBarConsumer =
    new DelegatingProgressBarConsumer({ text => logger.info(text) }, MaxProgressBarLength)

  override def cmdConfigures: Option[CmdConfigures] = _cmdConfigures

  override def configureCmds(cmdConfigures: CmdConfigures): DockerController = {
    this._cmdConfigures = Some(cmdConfigures)
    this
  }

  protected def newCreateContainerCmd(): CreateContainerCmd = {
    dockerClient
      .createContainerCmd(repoTag)
  }

  protected def newRemoveContainerCmd(): RemoveContainerCmd = {
    require(containerId != null)
    dockerClient.removeContainerCmd(containerId)
  }

  protected def newInspectContainerCmd(): InspectContainerCmd = {
    require(containerId != null)
    dockerClient.inspectContainerCmd(containerId)
  }

  protected def newListImagesCmd(): ListImagesCmd = {
    dockerClient.listImagesCmd()
  }

  protected def newPullImageCmd(): PullImageCmd = {
    require(imageName != null)
    val cmd = dockerClient.pullImageCmd(imageName)
    tag.fold(cmd)(t => cmd.withTag(t))
  }

  protected def newLogContainerCmd(): LogContainerCmd = {
    require(containerId != null)
    dockerClient
      .logContainerCmd(containerId)
      .withStdOut(true)
      .withStdErr(true)
      .withFollowStream(true)
      .withTailAll()
  }

  protected def newStartContainerCmd(): StartContainerCmd = {
    require(containerId != null)
    dockerClient.startContainerCmd(containerId)
  }

  protected def newStopContainerCmd(): StopContainerCmd = {
    require(containerId != null)
    dockerClient.stopContainerCmd(containerId)
  }

  override def createContainer(f: CreateContainerCmd => CreateContainerCmd): DockerController = {
    logger.debug("createContainer --- start")
    val configureFunction: CreateContainerCmd => CreateContainerCmd =
      cmdConfigures.map(_.createContainerCmdConfigure).getOrElse(identity)
    _containerId = f(configureFunction(newCreateContainerCmd())).exec().getId
    logger.debug("createContainer --- finish")
    this
  }

  override def removeContainer(f: RemoveContainerCmd => RemoveContainerCmd): DockerController = {
    logger.debug("removeContainer --- start")
    val configureFunction: RemoveContainerCmd => RemoveContainerCmd =
      cmdConfigures.map(_.removeContainerCmdConfigure).getOrElse(identity)
    f(configureFunction(newRemoveContainerCmd())).exec()
    logger.debug("removeContainer --- finish")
    this
  }

  override def inspectContainer(f: InspectContainerCmd => InspectContainerCmd): InspectContainerResponse = {
    logger.debug("inspectContainer --- start")
    val configureFunction: InspectContainerCmd => InspectContainerCmd =
      cmdConfigures.map(_.inspectContainerCmdConfigure).getOrElse(identity)
    val result = f(configureFunction(newInspectContainerCmd())).exec()
    logger.debug("inspectContainer --- finish")
    result
  }

  override def listImages(f: ListImagesCmd => ListImagesCmd): Vector[Image] = {
    logger.debug("listImages --- start")
    val configureFunction: ListImagesCmd => ListImagesCmd =
      cmdConfigures.map(_.listImageCmdConfigure).getOrElse(identity)
    val result = f(configureFunction(newListImagesCmd())).exec().asScala.toVector
    logger.debug("listImages --- finish")
    result
  }

  override def existsImage(p: Image => Boolean): Boolean = {
    logger.debug("exists --- start")
    val result = listImages().exists(p)
    logger.debug("exists --- finish")
    result
  }

  override def pullImageIfNotExists(f: PullImageCmd => PullImageCmd): DockerController = {
    logger.debug("pullImageIfNotExists --- start")
    if (!existsImage(p => p.getRepoTags.contains(repoTag))) {
      pullImage(f)
    }
    logger.debug("pullImageIfNotExists --- finish")
    this
  }

  override def pullImage(f: PullImageCmd => PullImageCmd): DockerController = {
    logger.debug("pullContainer --- start")
    val progressBarMap = mutable.Map.empty[String, ProgressBar]
    f(newPullImageCmd())
      .exec(new ResultCallback.Adapter[PullResponseItem] {
        override def onNext(frame: PullResponseItem): Unit = {
          if (frame.getProgressDetail != null) {
            val max     = frame.getProgressDetail.getTotal
            val current = frame.getProgressDetail.getCurrent
            val progressBar: ProgressBar = progressBarMap.getOrElseUpdate(
              frame.getId,
              newProgressBar(frame, max)
            )
            progressBar.maxHint(max).stepTo(current)
          }
        }
      })
      .awaitCompletion()

    progressBarMap.foreach {
      case (_, progressBar: ProgressBar) =>
        progressBar.close()
    }
    logger.debug("pullContainer --- finish")
    this
  }

  private def newProgressBar(frame: PullResponseItem, max: lang.Long) = {
    new ProgressBarBuilder()
      .setTaskName(s"pull image: ${frame.getStatus}, ${frame.getId}")
      .setStyle(ProgressBarStyle.ASCII)
      .setConsumer(progressBarConsumer)
      .setInitialMax(max)
      .build()
  }

  override def startContainer(f: StartContainerCmd => StartContainerCmd): DockerController = {
    logger.debug("startContainer --- start")
    val configureFunction: StartContainerCmd => StartContainerCmd =
      cmdConfigures.map(_.startContainerCmdConfigure).getOrElse(identity)
    f(configureFunction(newStartContainerCmd())).exec()
    logger.debug("startContainer --- finish")
    this
  }

  override def stopContainer(f: StopContainerCmd => StopContainerCmd): DockerController = {
    logger.debug("stopContainer --- start")
    val configureFunction: StopContainerCmd => StopContainerCmd =
      cmdConfigures.map(_.stopContainerCmdConfigure).getOrElse(identity)
    f(configureFunction(newStopContainerCmd())).exec()
    logger.debug("stopContainer --- finish")
    this
  }

  override def awaitCondition(duration: Duration)(predicate: Frame => Boolean): DockerController = {
    logger.debug("awaitCompletion --- start")
    val frameQueue: LinkedBlockingQueue[Frame] = new LinkedBlockingQueue[Frame]()

    newLogContainerCmd().exec(new ResultCallback.Adapter[Frame] {

      override def onNext(frame: Frame): Unit = {
        frameQueue.add(frame)
      }

    })

    @volatile var terminate = false
    val waiter = new Runnable {
      override def run(): Unit = {
        @tailrec
        def loop(): Unit = {
          if (!terminate && {
                val frame = frameQueue.poll(outputFrameInterval.toMillis, TimeUnit.MILLISECONDS)
                if (frame != null) {
                  logger.debug(frame.toString)
                  !predicate(frame)
                } else true
              }) {
            loop()
          }
        }
        try {
          loop()
        } catch {
          case _: InterruptedException =>
            logger.debug("interrupted")
          case ex: Throwable =>
            logger.debug("occurred error", ex)
            throw ex
        }
      }
    }

    val thread = new Thread(waiter)
    thread.start()
    if (duration.isFinite) {
      val timer = new Timer()
      timer.schedule(new TimerTask {
        override def run(): Unit = {
          terminate = true
          thread.interrupt()
        }
      }, duration.toMillis)
      timer.cancel()
    }
    thread.join()
    logger.debug("awaitCompletion --- finish")
    this
  }

}
