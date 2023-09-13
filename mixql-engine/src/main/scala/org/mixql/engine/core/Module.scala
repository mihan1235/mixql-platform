package org.mixql.engine.core

import com.github.nscala_time.time.Imports.{DateTime, richReadableInstant, richReadableInterval}
import com.typesafe.config.{Config, ConfigFactory}
import org.mixql.engine.core.logger.ModuleLogger
import org.zeromq.{SocketType, ZMQ}
import org.mixql.remote.messages.module.worker.{IWorkerSendToClient, SendMsgToPlatform, WorkerFinished}
import org.mixql.remote.RemoteMessageConverter
import org.mixql.remote.messages.Message
import org.mixql.remote.messages.broker.{IBrokerSender, PlatformPongHeartBeat}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.{Random, Try}
import org.mixql.remote.messages.client.{
  Execute,
  ExecuteFunction,
  GetDefinedFunctions,
  IModuleReceiver,
  IWorkerReceiver,
  ShutDown
}
import org.mixql.remote.messages.module.{
  ExecuteResult,
  ExecuteResultFailed,
  ExecutedFunctionResult,
  ExecutedFunctionResultFailed,
  GetDefinedFunctionsError,
  IModuleSendToClient
}
import org.mixql.remote.messages.module.toBroker.{
  EngineFailed,
  EngineIsReady,
  EnginePingHeartBeat,
  IBrokerReceiverFromModule
}

class Module(executor: IModuleExecutor, identity: String, host: String, port: Int)(implicit logger: ModuleLogger) {
  val config: Config = ConfigFactory.load()
  var ctx: ZMQ.Context = null
  implicit var server: ZMQ.Socket = null
  var poller: ZMQ.Poller = null
  var workerPoller: ZMQ.Poller = null

  val pollerTimeout: Long = Try(config.getLong("org.mixql.engine.module.pollerTimeout")).getOrElse(1000)

  val workerPollerTimeout: Long = Try(config.getLong("org.mixql.engine.module.workerPollerTimeout")).getOrElse({
    1500
  })

  private val heartBeatInterval: Long = {
    Try(config.getLong("org.mixql.engine.module.heartBeatInterval")).getOrElse(16500)
  }
  private var processStart: DateTime = null
  private val livenessInit: Int = Try(config.getInt("org.mixql.engine.module.liveness")).getOrElse(3)
  private var liveness: Int = livenessInit

  import logger._

  def startServer(): Unit = {
    logInfo(s"Starting main client")

    logInfo(s"host of server is " + host + " and port is " + port.toString)

    try {
      ctx = ZMQ.context(1)
      server = ctx.socket(SocketType.DEALER)
      // set identity to our socket, if it would not be set,
      // then it would be generated by ROUTER socket in broker object on server

      server.setIdentity(identity.getBytes)
      logInfo(s"connected: " + server.connect(s"tcp://$host:${port.toString}"))
      logInfo(s"Connection established.")

      logDebug(s"Setting processStart for timer")
      // Set timer
      processStart = DateTime.now()

      logInfo(s"Setting poller")
      poller = ctx.poller(1)

      logInfo(s"Setting workers poller")
      workerPoller = ctx.poller(14)

      logInfo(s"Register server's socket pollin in poller")
      val serverPollInIndex = poller.register(server, ZMQ.Poller.POLLIN)

      logInfo(s"Sending READY message to server's broker")
      sendMsgToPlatformBroker(new EngineIsReady(identity), logger)

      while (true) {
        val rc = poller.poll(pollerTimeout)
        var rcWorkers = -1;
        if (workerPoller.getSize != 0)
          rcWorkers = workerPoller.poll(workerPollerTimeout)
        //        if (rc == 1) throw BrakeException()
        if (poller.pollin(serverPollInIndex)) {
          logDebug("Setting processStart for timer, as message was received")
//          val (clientAdrressTmp, msg, pongHeartBeatMsg) =
          readMsgFromServerBroker(logger) match {
            case m: IBrokerSender => // got pong heart beat message
              logDebug(s"got broker's service message")
              reactOnReceivedBrokerMsg(m)
            case m: IModuleReceiver => // got protobuf message
              reactOnReceivedMsgForEngine(m)
          }
          processStart = DateTime.now()
          liveness = livenessInit
        } else {
          val elapsed = (processStart to DateTime.now()).millis
          logDebug(s"elapsed: " + elapsed)

          if (elapsed >= heartBeatInterval) {
            processStart = DateTime.now()
            logDebug(s"heartbeat work. Sending heart beat. Liveness: " + liveness)
            sendMsgToPlatformBroker(new EnginePingHeartBeat(identity), logger)
            liveness = liveness - 1
            logDebug(s"heartbeat work. After sending heart beat. Liveness: " + liveness)
          }

          if (liveness < 0) {
            logError(s"heartbeat failure, can't reach server's broker. Shutting down")
            throw new BrakeException()
          }
        }

        if (rcWorkers > 0) {
          for (index <- 0 until workerPoller.getSize) {
            if (workerPoller.pollin(index)) {
              val workerSocket = workerPoller.getSocket(index)
              val msg: Message = RemoteMessageConverter.unpackAnyMsgFromArray(workerSocket.recv(0))
              msg match {
                case m: WorkerFinished =>
                  logInfo(
                    "Received message WorkerFinished from worker " + m.workerIdentity() +
                      " Remove socket from workersMap"
                  )
                  workersMap.remove(m.Id)
                  logInfo("Unregister worker's " + m.workerIdentity() + " socket from workerPoller")
                  workerPoller.unregister(workerSocket)
                  logInfo("Closing worker's " + m.workerIdentity() + " socket")
                  workerSocket.close()
                case m: SendMsgToPlatform =>
                  logInfo(
                    "Received message SendMsgToPlatform from worker " + m.workerIdentity() +
                      " and send it to platform"
                  )
                  sendMsgToClient(m.msg, logger)
                case m: IWorkerSendToClient =>
                  logInfo(
                    "Received message of type IWorkerSendToPlatform from worker " + m.workerIdentity() +
                      s" and proxy it (type: ${m.`type`()}) to platform"
                  )
                  sendMsgToClient(m, logger)
              }
            }
          }
        }
      }
    } catch {
      case _: BrakeException => logDebug(s"BrakeException")
      case ex: Exception =>
        logError(s"Error: " + ex.getMessage)
        sendMsgToPlatformBroker(
          new EngineFailed(
            identity,
            s"Module $identity to broker: fatal error: " +
              ex.getMessage
          ),
          logger
        )
    } finally {
      close()
    }
    logInfo(s"Stopped.")
  }

  private def reactOnReceivedBrokerMsg(message: Message): Unit = {
    message match {
      case _: PlatformPongHeartBeat => logDebug(s"got pong heart beat message from broker server")
    }
  }

  private def reactOnReceivedMsgForEngine(message: IModuleReceiver): Unit = {
    import scala.util.{Success, Failure}
    message match {
      case msg: Execute => reactOnExecuteMessageAsync(msg)
      case _: ShutDown =>
        logInfo(s"Started shutdown")
        try {
          executor.reactOnShutDown(identity, message.clientIdentity(), logger)
        } catch {
          case e: Throwable =>
            logWarn(
              "Warning: error while reacting on shutdown: " +
                e.getMessage
            )
        }
        throw new BrakeException()
      case msg: ExecuteFunction => reactOnExecuteFunctionMessageAsync(msg)
      case _: GetDefinedFunctions =>
        try {
          sendMsgToClient(executor.reactOnGetDefinedFunctions(identity, message.clientIdentity(), logger), logger)
        } catch {
          case e: Throwable =>
            sendMsgToClient(
              new GetDefinedFunctionsError(
                s"Module $identity to ${message.clientIdentity()}: error while reacting on getting" +
                  " functions list" + e.getMessage,
                message.clientIdentity()
              ),
              logger
            )
        }
      case msg: IWorkerReceiver => sendMessageToWorker(msg)
    }
  }

  def sendMessageToWorker(msg: IWorkerReceiver) = {
    val workersName = msg.workerIdentity()
    logInfo(
      s"received message ${msg.`type`()} from platfrom to workers-future-$workersName " +
        "Sending it to worker"
    )

    val workerSocket = workersMap(workersName)
    workerSocket.send(msg.toByteArray)
  }

  def reactOnExecuteMessageAsync(msg: Execute) = {
    reactOnRemoteMessageAsync(
      msg.clientIdentity(),
      (workersId, ctxPlatform) => {
        logInfo(s"[workers-future-$workersId]: triggering onExecute")
        executor.reactOnExecuteAsync(msg, identity, msg.clientIdentity(), logger, ctxPlatform)
      },
      (value, socket, workerID) => {
        socket.send(
          new SendMsgToPlatform(new ExecuteResult(msg.statement, value, msg.clientIdentity()), workerID).toByteArray
        )
      },
      (ex: Throwable, socket, workerID) => {
        socket.send(
          new SendMsgToPlatform(
            new ExecuteResultFailed(
              s"Module $identity to ${msg.clientIdentity()}: error while reacting on execute: " +
                ex.getMessage,
              msg.clientIdentity()
            ),
            workerID
          ).toByteArray
        )
      }
    )
  }

  def reactOnExecuteFunctionMessageAsync(msg: ExecuteFunction) = {
    reactOnRemoteMessageAsync(
      msg.clientIdentity(),
      (workersID, ctxPlatform) => {
        logInfo(s"[workers-future-$workersID]: triggering onExecuteFunction")
        executor.reactOnExecuteFunctionAsync(msg, identity, msg.clientIdentity(), logger, ctxPlatform)
      },
      (value, socket, workerID) => {
        socket.send(
          new SendMsgToPlatform(new ExecutedFunctionResult(msg.name, value, msg.clientIdentity()), workerID).toByteArray
        )
      },
      (e: Throwable, socket, workerID) => {
        socket.send(
          new SendMsgToPlatform(
            new ExecutedFunctionResultFailed(
              s"Module $identity to ${msg.clientIdentity()}: error while reacting on execute function" +
                s"${msg.name}: " + e.getMessage,
              msg.clientIdentity()
            ),
            workerID
          ).toByteArray
        )
      }
    )
  }

  def reactOnRemoteMessageAsync(clientAddress: String,
                                executeFunc: (String, PlatformContext) => Message,
                                onSuccess: (Message, ZMQ.Socket, String) => Unit,
                                onFailure: (Throwable, ZMQ.Socket, String) => Unit): Unit = {
    import scala.util.{Success, Failure}
    val workersName = generateUnusedWorkersName()
    logInfo("Creating worker " + workersName)
    logInfo(s"Register module's pair socket pollin in workersPoller for worker " + workersName)
    val workerSocket = ctx.socket(SocketType.PAIR)
    val pairPollInIndex = workerPoller.register(workerSocket, ZMQ.Poller.POLLIN)
    workerSocket.bind(s"inproc://$workersName")
    workersMap.put(workersName, workerSocket)
    var futurePairSocket: ZMQ.Socket = null
    Future {
      logInfo(s"[workers-future-$workersName]: Creating future's pair socket for communicating with module")
      futurePairSocket = ctx.socket(SocketType.PAIR)
      logInfo(s"[workers-future-$workersName]: Bind future's pair socket in inproc://$workersName")
      futurePairSocket.connect(s"inproc://$workersName")
      executeFunc(workersName, new PlatformContext(futurePairSocket, workersName, clientAddress))
    }.onComplete {
      case Success(value) => // sendMsgToServerBroker(value, clientAddress, logger)
        onSuccess(value, futurePairSocket, workersName)
        logInfo(s"[workers-future-$workersName]: Sending WorkerFinished to inproc://$workersName")
        futurePairSocket.send(new WorkerFinished(workersName).toByteArray)
        logInfo(s"[workers-future-$workersName]: Close future's pair socket inproc://$workersName")
        futurePairSocket.close()
      case Failure(ex) => // sendMsgToServerBroker(errorFunc(ex), clientAddress, logger)
        onFailure(ex, futurePairSocket, workersName)
        logInfo(s"[workers-future-$workersName]: Sending WorkerFinished to inproc://$workersName")
        futurePairSocket.send(new WorkerFinished(workersName).toByteArray)
        logInfo(s"[workers-future-$workersName]: Close future's pair socket inproc://$workersName")
        futurePairSocket.close()
    }
  }

  def sendMsgToPlatformBroker(msg: IBrokerReceiverFromModule, logger: ModuleLogger): Boolean = {
    import logger._
    logDebug(s"sendMsgToPlatformBroker: Send msg to server ")
    server.send(msg.toByteArray())
  }

  def sendMsgToClient(msg: IModuleSendToClient, logger: ModuleLogger): Boolean = {
    import logger._
    logDebug(s"sendMsgToClient: Send msg to server ")
    server.send(msg.toByteArray())
  }

  def readMsgFromServerBroker(logger: ModuleLogger): Message = {
    import logger._
    ///////////////////////////////////////////////////////////////////////////////////////
    // FOR PROTOCOL SEE BOOK OReilly ZeroMQ Messaging for any applications 2013 ~page 100//
    // From server broker messenger we get msg with such body:                           //
    // identity frame                                                                    //
    // empty frame --> delimiter                                                         //
    // data ->                                                                           //
    ///////////////////////////////////////////////////////////////////////////////////////

    ///////////////////////////////// Identity frame of engine/////////////////////////////
    val identity = server.recv(0)
    val identityStr = new String(identity)
    logDebug(s"readMsgFromServerBroker: received Identity of engine " + identityStr)
    ///////////////////////////////////////////////////////////////////////////////////////

    ///////////////////////////////// MSG /////////////////////////////////////////////////
    val request = server.recv(0)
    val requestStr = new String(request)
    logDebug(s"have received message from server: " + requestStr)
    ///////////////////////////////////////////////////////////////////////////////////////
    RemoteMessageConverter.unpackAnyMsgFromArray(request)
  }

  // key -> workers unique name
  // int -> poll in index of pair to communicate with this worker
  val workersMap: mutable.Map[String, ZMQ.Socket] = mutable.Map()
  val r: Random.type = scala.util.Random

  def generateUnusedWorkersName(): String = {
    val numPattern = "[0-9]+".r
    val ids = workersMap.keys.map(name => numPattern.findFirstIn(name).get.toInt)

    var foundUniqueId = false
    var id = -1;
    while (!foundUniqueId) {
      id = r.nextInt().abs
      ids.find(p => p == id) match {
        case Some(_) =>
        case None    => foundUniqueId = true
      }
    }
    s"worker$id"
  }

  def close(): Unit = {
    import scala.util.Try
    Try(if (server != null) {
      logInfo(s"finally close server")
      server.close()
    })

    if (workersMap.nonEmpty) {
      workersMap.foreach(worker => {
        Try(worker._2.close())
      })
    }

    Try(if (poller != null) {
      logInfo(s"finally close poller")
      poller.close()
    })

    Try(if (workerPoller != null) {
      logInfo(s"finally close workerPoller")
      workerPoller.close()
    })

    try {
      if (ctx != null) {
        logInfo(s"finally close context")
        //        implicit val ec: scala.concurrent.ExecutionContext =
        //          scala.concurrent.ExecutionContext.global
//        Await.result(
//          Future {
        ctx.close()
//          },
//          scala.concurrent.duration.Duration(5000, "millis")
//        )
      }
    } catch {
      case _: Throwable => logError(s"tiemout of closing context exceeded:(")
    }
  }
}
