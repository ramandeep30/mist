package io.hydrosphere.mist.utils.akka

import akka.pattern.pipe
import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props, ReceiveTimeout, SupervisorStrategy, Terminated, Timers}
import io.hydrosphere.mist.utils.Logger

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

class RestartSupervisor(
  name: String,
  start: () => Future[ActorRef],
  timeout: FiniteDuration
) extends Actor with ActorLogging with Timers {

  override def receive: Receive = init

  import context._
  import RestartSupervisor._

  private def init: Receive = {
    case Event.Start(req) =>
      start().map(Event.Started) pipeTo self
      context become await(Some(req))
  }

  private def await(req: Option[Promise[ActorRef]]): Receive = {
    case Event.Started(ref) =>
      req.foreach(_.success(self))
      context watch ref
      context become proxy(ref)

    case akka.actor.Status.Failure(e) =>
      req.foreach(_.failure(e))
      log.error(e, "Starting child for {} failed", name)
      timers.startSingleTimer("timeout", Event.Timeout, timeout)
      context become restartTimeout
  }

  private def proxy(ref: ActorRef): Receive = {
    case Terminated(_) =>
      log.error(s"Reference for {} was terminated. Restarting", name)
      timers.startSingleTimer("timeout", Event.Timeout, timeout)
      context become restartTimeout

    case x => ref.forward(x)
  }

  private def restartTimeout: Receive = {
    case Event.Timeout =>
      start().map(Event.Started) pipeTo self
      context become await(None)
  }
}

object RestartSupervisor {

  sealed trait Event
  object Event {
    final case class Start(req: Promise[ActorRef]) extends Event
    case object Restart extends Event
    final case class Started(ref: ActorRef) extends Event
    case object Timeout extends Event
  }


  def props(
    name: String,
    start: () => Future[ActorRef],
    timeout: FiniteDuration
  ): Props = {
    Props(classOf[RestartSupervisor], name, start, timeout)
  }

  def wrap(
    name: String,
    start: () => Future[ActorRef],
    timeout: FiniteDuration
  )(implicit af: ActorRefFactory): Future[ActorRef] = {

    val ref = af.actorOf(props(name, start, timeout))
    val promise = Promise[ActorRef]
    ref ! Event.Start(promise)
    promise.future
  }

  def wrap(
    name: String,
    start: () => Future[ActorRef]
  )(implicit af: ActorRefFactory): Future[ActorRef] = wrap(name, start, 5 seconds)(af)

}

