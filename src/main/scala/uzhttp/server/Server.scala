package uzhttp.server

import java.io.InputStream
import java.net.{InetSocketAddress, SocketAddress, URI}
import java.nio.ByteBuffer
import java.nio.channels._
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

import uzhttp.server.Server.Logging
import zio.ZIO.{effect, effectTotal}
import zio.blocking.{Blocking, effectBlocking, effectBlockingCancelable}
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.{Sink, Stream}
import zio.{Chunk, Has, IO, Promise, RIO, Ref, Semaphore, Task, UIO, URIO, ZIO, ZLayer, ZManaged}

class Server private (
  channel: ServerSocketChannel,
  requestHandler: Request => IO[HTTPError, Response],
  errorHandler: HTTPError => IO[Nothing, Response],
  config: Server.Config,
  closed: Promise[Throwable, Unit]
) {

  /**
    * @return The bound address of the server. This is useful if the port was configured to `0` in order to use an
    *         OS-selected free port.
    */
  def localAddress: Task[SocketAddress] = awaitUp *> effect(channel.getLocalAddress)

  /**
    * @return A task which will complete when the server's socket channel is open.
    */
  def awaitUp: Task[Unit] = effect(channel.isOpen).doUntil(identity).unit

  def uri: UIO[Option[URI]] = localAddress.map {
    case inet: InetSocketAddress => Some(new URI("http", null, inet.getHostName, inet.getPort, "/", null, null))
    case _ => None
  }.orElse(ZIO.none)

  /**
    * Shut down the server.
    */
  def shutdown(): UIO[Unit] = effect(if (channel.isOpen) channel.close()).to(closed).unit

  def awaitShutdown: IO[Throwable, Unit] = closed.await

  private def serve(): RIO[Logging with Blocking with Clock, Nothing] =
    Server.ChannelSelector(channel, requestHandler, errorHandler, config).use {
      selector =>
        uri.someOrFail(()).flatMap(uri => Logging.info(s"Server listening on $uri")).ignore *> selector.run
    }


}

object Server {

  case class Config(
    maxPending: Int,
    responseTimeout: Duration,
    connectionIdleTimeout: Duration = Duration(5, TimeUnit.SECONDS)
  )

  /**
    * Create a server [[Builder]] using the specified address.
    */
  def builder(address: InetSocketAddress): Builder[Any] = Builder(address)

  final case class Builder[-R] private[Server] (
    address: InetSocketAddress,
    config: Config = Config(maxPending = 0, responseTimeout = Duration.Infinity),
    requestHandler: PartialFunction[Request, ZIO[R, HTTPError, Response]] = PartialFunction.empty,
    errorHandler: HTTPError => ZIO[R, Nothing, Response] = defaultErrorFormatter,
    logger: ServerLogger[R] = ServerLogger.Default
  ) {

    /**
      * Set the address on which the server should listen, replacing the currently set address.
      * Specifying a port of `0` will cause the server to bind on an operating-system assigned free port.
      */
    def withAddress(address: InetSocketAddress): Builder[R] = copy(address = address)

    /**
      * Set the maximum number of pending connections. The default of `0` specifies a platform-specific default value.
      * @see ServerSocketChannel#bind(SocketAddress, Int)
      */
    def withMaxPending(maxPending: Int): Builder[R] = copy(config = config.copy(maxPending = maxPending))

    /**
      * Set the timeout before which the request handler must begin a response. The default is no timeout.
      */
    def withResponseTimeout(responseTimeout: Duration): Builder[R] = copy(config = config.copy(responseTimeout = responseTimeout))

    /**
      * Provide a total function which will handle all requests not handled by a previously given partial handler
      * given to [[handleSome]].
      */
    def handleAll[R1 <: R](handler: Request => ZIO[R1, HTTPError, Response]): Builder[R1] =
      copy(requestHandler = requestHandler orElse { case x => handler(x) })

    /**
      * Provide a partial function which will handle matched requests not already handled by a previously given
      * partial handler.
      */
    def handleSome[R1 <: R](handler: PartialFunction[Request, ZIO[R1, HTTPError, Response]]): Builder[R1] =
      copy(requestHandler = requestHandler orElse handler)

    /**
      * Provide an error formatter which turns an [[HTTPError]] from a failed request into a [[Response]] to be returned
      * to the client. The default error formatter returns a plaintext response indicating the error.
      */
    def errorResponse[R1 <: R](errorHandler: HTTPError => URIO[R1, Response]): Builder[R1] =
      copy(errorHandler = errorHandler)

    /**
      * Provide a complete logger, replacing the current logger. This is useful if you want to use one of the
      * default loggers: [[ServerLogger.Default]] (the default), [[ServerLogger.Quiet]] (logs errors only), or
      * [[ServerLogger.Silent]] (logs nothing)
      */
    def withLogger[R1 <: R](logger: ServerLogger[R1]): Builder[R1] = copy(logger = logger)

    /**
      * Replace the current error logger with the given function, which receives a String and a Throwable and logs
      * it somehow (or does nothing).
      */
    def logErrors[R1 <: R](errorLogger: (String, Throwable) => URIO[R1, Unit]): Builder[R1] =
      copy(logger = logger.copy(error = errorLogger))

    /**
      * Replace the current info logger with the given function, which receives a String and logs it somehow (or does nothing)
      */
    def logInfo[R1 <: R](infoLogger: (=> String) => URIO[R1, Unit]): Builder[R1] =
      copy(logger = logger.copy(info = infoLogger))

    /**
      * Replace the current request logger with the given function, which receives:
      *   - The request
      *   - The response
      *   - The start duration – how long it took for the request handler to return a response
      *   - The finish duration – the duration between the request handler returning the response, and the response
      *     being completely sent to the client
      *
      * and logs it somehow (or does nothing)
      */
    def logRequests[R1 <: R](requestLogger: (Request, Response, Duration, Duration) => URIO[R1, Unit]): Builder[R1] =
      copy(logger = logger.copy(request = requestLogger))

    /**
      * Replace the current debug logger with the given function, which receives a String and logs it somehow (or does nothing)
      */
    def logDebug[R1 <: R](debugLogger: (=> String) => URIO[R1, Unit]): Builder[R1] =
      copy(logger = logger.copy(debug = debugLogger))

    private def build: ZManaged[R with Blocking with Logging, Throwable, Server] =
      mkSocket(address, config.maxPending)
        .flatMap {
          channel =>
            Promise.make[Throwable, Unit].toManaged_.flatMap {
              closed => ZIO.environment[R].flatMap {
                env => effectTotal(new Server(
                  channel,
                  (requestHandler orElse unhandled) andThen (_.provide(env)),
                  errorHandler andThen (_.provide(env)),
                  config,
                  closed))
              }.toManaged {
                server => Logging.info("Shutting down server") *> server.shutdown()
              }
            }
        }

    /**
      * Start the server and begin serving requests. The returned [[Server]] reference can be used to wait for the
      * server to be up, to retrieve the bound address, and to shut it down if desired. After using the returned
      * ZManaged, the server will be shut down (you can use ZManaged.useForever to keep the server running until
      * termination of your app)
      */
    def serve: ZManaged[R with Blocking with Clock, Throwable, Server] =
      ZManaged.environment[R].flatMap {
        R => build
          .flatMap(server => server.serve().forkManaged.as(server))
          .provideSomeLayer[R with Blocking with Clock](ZLayer.succeed(logger.provide(R)))
      }

  }

  private[server] trait ConnectionWriter {
    def write(bytes: Array[Byte]): Task[Unit]
    def writeByteBuffers(buffers: Stream[Throwable, ByteBuffer]): Task[Unit]
    def writeByteArrays(arrays: Stream[Throwable, Array[Byte]]): Task[Unit]
    def transferFrom(header: ByteBuffer, src: FileChannel): ZIO[Blocking, Throwable, Unit]
    def pipeFrom(header: ByteBuffer, is: InputStream, bufSize: Int): ZIO[Blocking, Throwable, Unit]
  }

  private[server] final class Connection private (
    inputBuffer: ByteBuffer,
    curReq: Ref[Either[(Int, List[String]), ContinuingRequest]],
    requestHandler: Request => IO[HTTPError, Response],
    errorHandler: HTTPError => UIO[Response],
    config: Config,
    private[Server] val channel: ReadableByteChannel with WritableByteChannel with SelectableChannel,
    locks: Connection.Locks,
    shutdown: Promise[Throwable, Unit]
  ) extends ConnectionWriter {

    import config._
    import locks._

    // assumes writeLock has been obtained
    private def writeInternal(buf: ByteBuffer): Task[Unit] = effect(channel.write(buf)).as(buf).doWhile(_.hasRemaining).unit

    def write(bytes: Array[Byte]): Task[Unit] = writeLock.withPermit {
      writeInternal(ByteBuffer.wrap(bytes))
    }

    def writeByteBuffers(buffers: Stream[Throwable, ByteBuffer]): Task[Unit] = writeLock.withPermit {
      buffers.foreach(writeInternal)
    }

    def writeByteArrays(arrays: Stream[Throwable, Array[Byte]]): Task[Unit] =
      writeByteBuffers(arrays.map(ByteBuffer.wrap))

    def transferFrom(header: ByteBuffer, src: FileChannel): ZIO[Blocking, Throwable, Unit] = writeLock.withPermit {
      ZIO.environment[Blocking].flatMap {
        blocking =>
          effectBlocking(src.size()).flatMap {
            size =>
              writeInternal(header) *> Stream.unfoldM(0L) {
                transferred =>
                  effectBlocking {
                    if (transferred < size) {
                      Some((), transferred + src.transferTo(transferred, size - transferred, channel))
                    } else {
                      None
                    }
                  }.provide(blocking)
              }.run(Sink.drain)
          }
      }
    }

    def pipeFrom(header: ByteBuffer, is: InputStream, bufSize: Int): ZIO[Blocking, Throwable, Unit] = if (bufSize <= 0) ZIO.unit else writeLock.withPermit {
      effectBlocking {
        val buf = new Array[Byte](bufSize)
        val byteBuf = ByteBuffer.wrap(buf)
        var numRead = is.read(buf)
        while (numRead != -1) {
          byteBuf.limit(numRead)
          byteBuf.position(0)
          channel.write(byteBuf)
          numRead = is.read(buf)
        }
      }
    }

    /**
      * Take n bytes from the top of the input buffer, and shift the remaining bytes (up to the buffer's position), if
      * any, to the beginning of the buffer. Afterward, the buffer's position will be after the end of the remaining bytes.
      *
      * PERF: A low-hanging performance improvement here would be to not shift and rewind the buffer until it reaches
      *       the end. That would add a bit more complexity, but could really boost performance.
      */
    private def takeAndRewind(n: Int) = {
      val arr = new Array[Byte](n)
      val pos = inputBuffer.position()
      val remainderLength = pos - n
      inputBuffer.rewind()
      inputBuffer.get(arr)

      if (remainderLength > 0) {
        val rem = inputBuffer.slice()
        rem.limit(remainderLength)
        inputBuffer.rewind()
        inputBuffer.put(rem)
      } else {
        inputBuffer.rewind()
      }
      arr
    }

    private val timeoutRequest: Request => ZIO[Clock, HTTPError, Response] = responseTimeout match {
      case Duration.Infinity => requestHandler
      case duration if duration.isZero => requestHandler
      case duration => requestHandler andThen
        (_.timeoutFail(RequestTimeout(s"Request could not be handled within ${duration.render}"))(duration))
    }

    private def handleRequest(req: Request) = requestLock.withPermit {
      timeoutRequest(req).catchAll(errorHandler).timed.tap {
        case (dur, rep) => Logging.debug {
          val size = rep.headers.get("Content-Length")
            .map(cl => try cl.toLong catch { case _: Throwable => -1 })
            .filterNot(_ < 0).map(humanReadableByteCountSI).getOrElse("(Unknown size)")
          s"${req.uri} ${rep.status} $size (${dur.render} to start)"
        }
      }.map {
        case (dur, rep) =>
          val shouldClose = req.version match {
            case Request.Http09 => true
            case Request.Http10 => !req.headers.get("Connection").contains("keepalive")
            case Request.Http11 => req.headers.get("Connection").contains("close")
          }
          if (!rep.closeAfter && shouldClose)
            dur -> (req, rep.addHeader("Connection", "close"))
          else dur -> (req, rep)
      }.flatMap {
        case (startDuration, (req, rep)) =>
          rep.writeTo(this).flatMap {
            _ =>
              if (rep.closeAfter)
                close()
              else
                ZIO.unit
          }.timed.flatMap {
            case (finishDuration, _) =>
              curReq.set(Left(0 -> Nil)) *> Logging.request(req, rep, startDuration, finishDuration)
          }
      }
    }

    val doRead: RIO[Logging with Blocking with Clock, Unit] = readLock.withPermit {
      def bytesReceived(numBytes: Int): ZIO[Logging with Blocking with Clock, HTTPError, Unit] = if (numBytes > 0) {
        curReq.get.flatMap {
          case Right(req) =>
            req.bytesRemaining.flatMap {
              case bytesRemaining if bytesRemaining <= numBytes =>
                val remainderLength = (numBytes - bytesRemaining).toInt
                val takeLength = numBytes - remainderLength
                val chunk = takeAndRewind(takeLength)
                req.submitBytes(Chunk.fromArray(chunk)) *> curReq.set(Left(0 -> Nil)) *> bytesReceived(remainderLength)

              case _ =>
                val chunk = takeAndRewind(numBytes)
                req.submitBytes(Chunk.fromArray(chunk))
            }

          case Left((prevPos, headerChunks)) =>
            // search for \r\n\r\n in the buffer to mark end of headers
            var found = -1
            var takeLimit = -1
            var idx = math.max(0, prevPos - 4)
            val end = inputBuffer.position() - 3
            while (found < 0 && idx < end) {
              if (inputBuffer.get(idx) == '\r') {
                takeLimit = idx
                idx += 1
                if (inputBuffer.get(idx) == '\n') {
                  idx += 1
                  if (inputBuffer.get(idx) == '\r') {
                    idx += 1
                    if (inputBuffer.get(idx) == '\n') {
                      found = idx
                    } else {
                      // this is illegal state – throw bad request?
                      idx += 1
                    }
                  } else {
                    idx += 1
                  }
                } else {
                  idx += 1
                }
              } else {
                idx += 1
              }
            }
            if (found >= 0) {
              // finished the headers – decide what kind of request it is and build the request
              val chunk = takeAndRewind(found + 1)
              val remainderLength = inputBuffer.position()
              val reqString = (new String(chunk, StandardCharsets.US_ASCII) :: headerChunks).reverse.mkString.trim()
              val mkReq = IO.fromEither(Request.NoBody.fromReqString(reqString)).flatMap {
                case Request.NoBody(method, uri, version, headers) if headers.get("Upgrade").contains("websocket") =>
                  for {
                    request <- Request.WebsocketRequest(method, uri, version, headers)
                    _       <- curReq.set(Right(request))
                    _       <- handleRequest(request).forkDaemon
                  } yield ()

                case Request.NoBody(method, uri, version, headers) if headers.contains("Content-Length") && headers("Content-Length") != "0" =>
                  for {
                    contentLength <- IO(headers("Content-Length").toLong).orElseFail(BadRequest("Couldn't parse Content-Length"))
                    request       <- Request.ReceivingBody.create(method, uri, version, headers, contentLength)
                    _             <- curReq.set(Right(request))
                    _             <- handleRequest(request).forkDaemon
                  } yield ()

                case request => handleRequest(request).forkDaemon
              }

              mkReq *> bytesReceived(remainderLength)
            } else {
              // PERF: A low-hanging performance improvement here would be to allow the buffer to fill up while waiting
              //       for headers. Often the size of headers will fit into the buffer, so we could avoid accumulating
              //       chunks and rewinding the buffer.
              if (takeLimit - 1 > 0) {
                // can safely take this chunk of header data and rewind the buffer – only take up to a \r to avoid splitting across the empty line chars
                val chunk = takeAndRewind(takeLimit - 1)
                val remainderLength = inputBuffer.position()
                curReq.set(Left(remainderLength -> (new String(chunk, StandardCharsets.US_ASCII) :: headerChunks))) *> bytesReceived(remainderLength)
              } else if (takeLimit < 0) {
                // can safely take the whole data and rewind the buffer
                val chunk = takeAndRewind(inputBuffer.position())
                curReq.set(Left(0 -> (new String(chunk, StandardCharsets.US_ASCII) :: headerChunks)))
              } else {
                // can't take anything, because the only characters are \r and \n (but not enough to end the headers). Defer until more bytes available.
                curReq.set(Left(inputBuffer.position() -> headerChunks))
              }
            }
        }
      } else ZIO.yieldNow

      effect(channel.read(inputBuffer)).flatMap {
        case -1 => close()
        case numBytes => bytesReceived(numBytes)
      }.catchAll {
        err => Logging.info(s"Closing connection due to read error: ${err.getMessage}") *> close()
      }
    }

    def close(): UIO[Unit] = writeLock.withPermit {
      shutdown.succeed(()).flatMap {
        case true  => effect(channel.close()).orDie
        case false => ZIO.unit
      }
    }

    val awaitShutdown: IO[Throwable, Unit] = shutdown.await

  }

  private object Connection {
    case class Locks(readLock: Semaphore, writeLock: Semaphore, requestLock: Semaphore)
    object Locks {
      def make: UIO[Locks] = ZIO.mapN(Semaphore.make(1), Semaphore.make(1), Semaphore.make(1))(Locks.apply)
    }

    def apply(channel: SocketChannel, requestHandler: Request => IO[HTTPError, Response], errorHandler: HTTPError => UIO[Response], config: Config): ZManaged[Clock, Nothing, Connection] = {
      for {
        curReq      <- Ref.make[Either[(Int, List[String]), ContinuingRequest]](Left(0 -> Nil))
        locks       <- Locks.make
        shutdown    <- Promise.make[Throwable, Unit]
        connection  = new Connection(ByteBuffer.allocate(8192), curReq, requestHandler, errorHandler, config, channel, locks, shutdown)
      } yield connection
    }.toManaged(_.close())
  }

  private class ChannelSelector(
    selector: Selector,
    serverSocket: ServerSocketChannel,
    ConnectKey: SelectionKey,
    requestHandler: Request => IO[HTTPError, Response],
    errorHandler: HTTPError => UIO[Response],
    config: Config
  ) {

    private def register(connection: Connection): ZManaged[Any, Throwable, SelectionKey] =
      effect(connection.channel.register(selector, SelectionKey.OP_READ, connection))
        .toManaged(key => effectTotal(key.cancel()))

    private def selectedKeys = effect {
      selector.synchronized {
        val k = selector.selectedKeys()
        val ks = k.toArray(Array.empty[SelectionKey])
        ks.foreach(k.remove)
        ks
      }
    }

    def select: RIO[Logging with Blocking with Clock, Unit] = effectBlockingCancelable(selector.select(500))(effectTotal(selector.wakeup()).unit).flatMap {
      case 0 =>
        ZIO.unit
      case _ => selectedKeys.flatMap {
        keys =>
          ZIO.foreachPar_(keys) {
            case ConnectKey =>
              effect(Option(serverSocket.accept())).catchAll {
                err =>
                  Logging.error("Error accepting connection; server socket is closed", err) *>
                    close() *>
                    ZIO.fail(())
              }.someOrFail(()).flatMap {
                conn =>
                  conn.configureBlocking(false)
                  Connection(conn, requestHandler, errorHandler, config).flatMap {
                    conn =>
                      register(conn).orDie.as(conn)
                  }.use(_.awaitShutdown).forkDaemon.unit
              }.forever.catchAll {
                _ => ZIO.unit
              }
            case key =>
              effect(key.attachment().asInstanceOf[Server.Connection]).flatMap {
                conn => conn.doRead.catchAll {
                  err => Logging.error(s"Error reading from connection", err) *> conn.close()
                }
              }
          }
      }
    }

    def close(): URIO[Logging, Unit] =
      ZIO.foreach(selector.keys().toIterable)(k => effect(k.cancel())).orDie *> effect(selector.close()).orDie *>
        effect(serverSocket.close()).orDie

    def run: RIO[Logging with Blocking with Clock, Nothing] = (select *> ZIO.yieldNow).forever
  }

  private object ChannelSelector {
    def apply(serverChannel: ServerSocketChannel, requestHandler: Request => IO[HTTPError, Response], errorHandler: HTTPError => UIO[Response], config: Config): ZManaged[Logging, Throwable, ChannelSelector] =
      effect(Selector.open())
        .toManaged(s => effectTotal(s.close()))
        .flatMap {
          selector =>
            val connectKey = serverChannel.register(selector, SelectionKey.OP_ACCEPT)
            ZIO.effectTotal(new ChannelSelector(selector, serverChannel, connectKey, requestHandler, errorHandler, config)).toManaged(_.close())
        }
  }

  type Logging = Has[ServerLogger[Any]]

  object Logging {
    def info(str: => String): URIO[Logging, Unit] = ZIO.accessM[Logging](_.get[ServerLogger[Any]].info(str))
    def request(req: Request, rep: Response, startDuration: Duration, finishDuration: Duration): URIO[Logging, Unit] = ZIO.accessM[Logging](_.get[ServerLogger[Any]].request(req, rep, startDuration, finishDuration))
    def debug(str: => String): URIO[Logging, Unit] = ZIO.accessM[Logging](_.get[ServerLogger[Any]].debug(str))
    def error(str: String, err: Throwable): URIO[Logging, Unit] = ZIO.accessM[Logging](_.get[ServerLogger[Any]].error(str, err))
  }


  case class ServerLogger[-R](
    info: (=> String) => ZIO[R, Nothing, Unit],
    request: (Request, Response, Duration, Duration) => ZIO[R, Nothing, Unit],
    error: (String, Throwable) => ZIO[R, Nothing, Unit],
    debug: (=> String) => ZIO[R, Nothing, Unit] = ServerLogger.noLog
  ) {
    def provide(R: R): ServerLogger[Any] = copy(
      info = info andThen (_.provide(R)),
      request = (req, rep, sd, fd) => request(req, rep, sd, fd).provide(R),
      error = (msg, err) => error(msg, err).provide(R),
      debug = debug andThen (_.provide(R))
    )
  }

  object ServerLogger {
    def defaultRequestLogger(req: Request, rep: Response, startDuration: Duration, finishDuration: Duration): UIO[Unit] = {
      val closedTag = if (rep.closeAfter) "(closed)" else "(keepalive)"
      val finishTime = finishDuration.render
      val totalTime = (startDuration + finishDuration).render
      effectTotal(System.err.println(s"[REQUEST] ${req.uri} ${rep.status} ($finishTime to finish, $totalTime total) $closedTag"))
    }

    val defaultInfoLogger: (=> String) => UIO[Unit] = str => effectTotal(System.err.println(s"[INFO]   $str"))
    val defaultErrorLogger: (String, Throwable) => UIO[Unit] = (str, err) => effectTotal { System.err.println(s"[ERROR]  $str"); err.printStackTrace(System.err) }
    val noLog: (=> String) => UIO[Unit] = _ => ZIO.unit
    val noLogRequests: (Request, Response, Duration, Duration) => UIO[Unit] = (_, _, _, _) => ZIO.unit

    lazy val Default: ServerLogger[Any] = ServerLogger(defaultInfoLogger, defaultRequestLogger, defaultErrorLogger, noLog)
    lazy val Debug: ServerLogger[Any] = Default.copy(debug = str => effectTotal(System.err.println(s"[DEBUG]  $str")))
    lazy val Quiet: ServerLogger[Any] = Default.copy(info = noLog, request = noLogRequests)
    lazy val Silent: ServerLogger[Any] = Quiet.copy(error = (_, _) => ZIO.unit)
  }

  private val unhandled: PartialFunction[Request, ZIO[Any, HTTPError, Nothing]] =
  { case req => ZIO.fail(NotFound(req.uri)) }

  private val defaultErrorFormatter: HTTPError => ZIO[Any, Nothing, Response] =
    err => ZIO.succeed(Response.plain(s"${err.statusCode} ${err.statusText}\n${err.getMessage}", status = err))

  private def mkSocket(address: InetSocketAddress, maxPending: Int): ZManaged[Blocking, Throwable, ServerSocketChannel] = effect {
    val socket = ServerSocketChannel.open()
    socket.configureBlocking(false)
    socket
  }.toManaged {
    channel => effect(channel.close()).orDie
  }.mapM {
    channel => effectBlocking(channel.bind(address, maxPending))
  }

}