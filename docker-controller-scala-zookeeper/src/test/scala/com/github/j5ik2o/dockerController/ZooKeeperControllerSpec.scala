package com.github.j5ik2o.dockerController

import com.github.j5ik2o.dockerController.WaitPredicates.WaitPredicate
import org.apache.zookeeper.{ WatchedEvent, Watcher, ZooKeeper }
import org.scalatest.freespec.AnyFreeSpec
import org.apache.zookeeper.Watcher.Event.KeeperState

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration.Duration

class ZooKeeperControllerSpec extends AnyFreeSpec with DockerControllerSpecSupport {
  lazy val hostPort: Int                            = RandomPortUtil.temporaryServerPort()
  lazy val zooKeeperController: ZooKeeperController = ZooKeeperController(dockerClient)(1, dockerHost, hostPort)

  override protected val dockerControllers: Vector[DockerController] = Vector(zooKeeperController)

  // val waitPredicate: WaitPredicate = WaitPredicates.forListeningHostTcpPort(dockerHost, minioPort)
  val waitPredicate: WaitPredicate = WaitPredicates.forLogMessageByRegex(ZooKeeperController.RegexForWaitPredicate)

  val waitPredicateSetting: WaitPredicateSetting = WaitPredicateSetting(Duration.Inf, waitPredicate)

  override protected val waitPredicatesSettings: Map[DockerController, WaitPredicateSetting] =
    Map(zooKeeperController -> waitPredicateSetting)

  "ZooKeeperControllerSpec" - {
    "run" in {
      var zk: ZooKeeper = null
      try {
        val connectionLatch = new CountDownLatch(1)
        zk = new ZooKeeper(s"$dockerHost:$hostPort", 3000, new Watcher {
          override def process(event: WatchedEvent): Unit = {
            logger.debug(s"event = $event")
            if (event.getState == KeeperState.SyncConnected)
              connectionLatch.countDown()
          }
        })
        connectionLatch.await(10, TimeUnit.SECONDS)
      } finally if (zk != null)
        zk.close()
    }
  }

}