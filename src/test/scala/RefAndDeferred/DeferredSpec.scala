package RefAndDeferred

import cats.effect.concurrent.Deferred
import cats.implicits._
import monix.eval.Task
import monix.execution.schedulers.TestScheduler
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable}

import scala.concurrent.duration._

class DeferredSpec extends BaseAsyncSpec {
  val testScheduler: TestScheduler = TestScheduler()
  val messageStream = ConcurrentSubject[String](MulticastStrategy.publish)
  val consumerError = new Throwable("Consumer setup failed")
  val consumerSetupWithFailure: Task[Consumer] = Consumer.setup.flatMap(_ => Task.raiseError(consumerError))

  class Consumer {
    def read: Task[Unit] = messageStream.mapEval(msg => Task(println(s"Received $msg"))).completedL
  }

  object Consumer {
    def setup: Task[Consumer] = Task.pure(new Consumer()).delayExecution(20.milliseconds)
  }

  class Producer {
    def write: Task[Unit] =
      Observable
        .interval(1.milliseconds)
        .map(index => s"Msg $index")
        .mapEval(msg => Task{
          println(s"Sending $msg")
          messageStream.onNext(msg)
        })
        .completedL
  }

  object Producer {
    def setup: Task[Producer] = Task.pure(new Producer()).delayExecution(10.milliseconds)
  }

  it("loses messages when producer setup is fast") {

    def consume: Task[Unit] = for {
      c <- Consumer.setup
      _ <- c.read
    } yield ()

    def produce: Task[Unit] = for {
      p <- Producer.setup
      _ <- p.write
    } yield ()

    def prog: Task[Unit] = for {
      _ <- consume.start
      _ <- produce.start
    } yield ()

    prog.runToFuture(testScheduler)
    testScheduler.tick(50.milliseconds)
    succeed
  }

  it("waits for consumer setup to finish before publishing") {
    def consume(done: Deferred[Task, Unit]): Task[Unit] = for {
      c <- Consumer.setup
      _ <- done.complete(())
      _ <- c.read
    } yield ()

    def produce(done: Deferred[Task, Unit]): Task[Unit] = for {
      p <- Producer.setup
      _ <- done.get
      _ <- p.write
    } yield ()

    def prog: Task[Unit] = for {
      d <- Deferred[Task, Unit]
      _ <- consume(d).start
      _ <- produce(d).start
    } yield ()

    prog.runToFuture(testScheduler)
    testScheduler.tick(50.milliseconds)
    succeed
  }

  it("blocks the producer when the consumer setup fails") {

    def consume(done: Deferred[Task, Unit]): Task[Unit] = for {
      c <- consumerSetupWithFailure
      _ <- done.complete(())
      _ <- c.read
    } yield ()

    def produce(done: Deferred[Task, Unit]): Task[Unit] = for {
      p <- Producer.setup
      _ <- Task(println("Producer is waiting..."))
      _ <- done.get.flatTap(_ => Task(println("Producer resume!")))
      _ <- Task(println("Producer start writing..."))
      _ <- p.write
    } yield ()

    def prog: Task[Unit] = for {
      d <- Deferred[Task, Unit]
      _ <- consume(d).start
      _ <- produce(d).start
    } yield ()

    prog.runToFuture(testScheduler)
    testScheduler.tick(50.milliseconds)
    succeed
  }

  it("Propagate errors and calls complete") {

    def consume(done: Deferred[Task, Either[Throwable, Unit]]): Task[Unit] = for {
        c <- consumerSetupWithFailure.attempt // c: Either[Throwable, Consumer]
        _ <- done.complete(c.void)
        _ <- Task.fromTry(c.toTry).flatMap(_.read)
      } yield ()

    def produce(done: Deferred[Task, Either[Throwable, Unit]]): Task[Unit] = for {
      p <- Producer.setup
      _ <- Task(println("Producer is waiting..."))
      _ <- done.get.flatTap(_ => Task(println("Producer resume!"))).rethrow
      _ <- Task(println("Producer start writing..."))
      _ <- p.write
    } yield ()

    def prog: Task[Unit] = for {
      d <- Deferred[Task, Either[Throwable, Unit]]
      _ <- consume(d).start
      _ <- produce(d).start
    } yield ()

    prog.runToFuture(testScheduler)
    testScheduler.tick(100.milliseconds)
    succeed
  }
}
