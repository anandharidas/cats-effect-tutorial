/*
 * Copyright (c) 2018 Luis Rodero-Merino
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package catsEffectTutorial

import cats.effect._
import cats.effect.concurrent.MVar
import cats.implicits._

import java.io._
import java.net._

/** Similar to [[EchoServerV2_GracefulStop]], with an added feature: when shutdown, it closes all the open client
 *  connections.
 */
object EchoServerV3_ClosingClientsOnShutdown extends IOApp {

  def echoProtocol(clientSocket: Socket, stopFlag: MVar[IO, Unit]): IO[Unit] = {
  
    def close(reader: BufferedReader, writer: BufferedWriter): IO[Unit] = 
      (IO{reader.close()}, IO{writer.close()})
        .tupled                        // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
        .map(_ => ())                  // From IO[(Unit, Unit)] to IO[Unit]
        .handleErrorWith(_ => IO.unit) // Swallowing up any possible error
  
    def loop(reader: BufferedReader, writer: BufferedWriter): IO[Unit] =
      for {
        _     <- IO.cancelBoundary
        lineE <- IO{ reader.readLine() }.attempt
        _     <- lineE match {
                   case Right(line) => line match {
                     case "STOP" => stopFlag.put(()) // Stopping server! Also put(()) returns IO[Unit] which is handy as we are done
                     case ""     => IO.unit          // Empty line, we are done
                     case _      => IO{ writer.write(line); writer.newLine(); writer.flush() } *> loop(reader, writer)
                   }
                   case Left(e) =>
                     for { // readLine() failed, stopFlag will tell us whether this is a graceful shutdown
                       isEmpty <- stopFlag.isEmpty
                       _       <- if(!isEmpty) IO.unit  // stopFlag is set, cool, we are done
                                  else IO.raiseError(e) // stopFlag not set, must raise error
                     } yield ()
                 }
      } yield ()
  
    val readerIO = IO{ new BufferedReader(new InputStreamReader(clientSocket.getInputStream())) }
    val writerIO = IO{ new BufferedWriter(new PrintWriter(clientSocket.getOutputStream())) }
  
    import cats.effect.ExitCase.{Completed, Error, Canceled}
    (readerIO, writerIO)
      .tupled       // From (IO[BufferedReader], IO[BufferedWriter]) to IO[(BufferedReader, BufferedWriter)]
      .bracket {
        case (reader, writer) => loop(reader, writer)  // Let's get to work!
      } {
        case (reader, writer) => close(reader, writer) // We are done, closing the streams
      }
  }

  def serve(serverSocket: ServerSocket, stopFlag: MVar[IO, Unit]): IO[Unit] = {

    def close(socket: Socket): IO[Unit] = 
      IO{ socket.close() }.handleErrorWith(_ => IO.unit)

    for {
      _       <- IO.cancelBoundary
      socketE <- IO{ serverSocket.accept() }.attempt
      _       <- socketE match {
        case Right(socket) =>
          for { // accept() succeeded, we attend the client in its own Fiber
            fiber <- echoProtocol(socket, stopFlag)
                       .guarantee(close(socket))      // We close the server whatever happens
                       .start                         // Client attended by its own Fiber
            _     <- (stopFlag.read *> close(socket)) 
                       .start                         // Another Fiber to cancel the client when stopFlag is set
            _     <- serve(serverSocket, stopFlag)    // Looping to wait for the next client connection
          } yield ()
        case Left(e) =>
          for { // accept() failed, stopFlag will tell us whether this is a graceful shutdown
            isEmpty <- stopFlag.isEmpty
            _       <- if(!isEmpty) IO.unit  // stopFlag is set, cool, we are done
                       else IO.raiseError(e) // stopFlag not set, must raise error
          } yield ()
      }
    } yield ()
  }

  def server(serverSocket: ServerSocket): IO[ExitCode] = 
    for {
      stopFlag    <- MVar[IO].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start
      _           <- stopFlag.read *> IO{println(s"Stopping server")}
      _           <- serverFiber.cancel
    } yield ExitCode.Success

  override def run(args: List[String]): IO[ExitCode] = {

    def close(socket: ServerSocket): IO[Unit] =
      IO{ socket.close() }.handleErrorWith(_ => IO.unit)

    IO{ new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) }
      .bracket {
        serverSocket => server(serverSocket)
      } {
        serverSocket => close(serverSocket)  *> IO{println("Server finished") }
      }
  }
}