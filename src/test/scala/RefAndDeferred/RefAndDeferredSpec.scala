package RefAndDeferred

import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import monix.eval.Task
import monix.execution.schedulers.TestScheduler

import scala.concurrent.duration._

class RefAndDeferredSpec extends BaseAsyncSpec {
  val testScheduler: TestScheduler = TestScheduler()
  val fastfetch = Task.pure("Fetch succeeded")
  val slowFetch = fastfetch.delayResult(50.milliseconds)
  val error = new Throwable("Could not fetch")

  sealed trait State
  case class Value(v: String) extends State
  case class Updating(d: Deferred[Task, Either[Throwable, String]]) extends State
  case object NoValue extends State

  trait Cached[Task[_], A] {
    def get: Task[A]
    def expire: Task[Unit]
  }

  object Cache {
    def apply(state: Ref[Task, State], fetchTask: Task[String]) = {
      new Cached[Task, String] {

        def get: Task[String] =
          Deferred[Task, Either[Throwable, String]].flatMap { newV =>
          state.modify {
              case st@Value(v) => st -> Task.pure(v)
              case st@Updating(inFlight) => st -> inFlight.get.rethrow
              case NoValue => Updating(newV) -> fetch(newV).rethrow
            }
          }.flatten

        private def fetch(d: Deferred[Task, Either[Throwable, String]]): Task[Either[Throwable, String]] = {
          println("fetching...")

          val task = for {
            r <- fetchTask.attempt
            _ <- state.set {
              r match {
                case Left(_) => NoValue
                case Right(v) => Value(v)
              }
            }
            _ <- d.complete(r)
          } yield r

          task.doOnCancel {
            state.modify { state =>
              println(s"canceling...$state")

              state match {
                case st@Value(v) =>
                  st -> d.complete(v.asRight).attempt.void

                case NoValue | Updating(_) =>
                  NoValue -> d.complete(error.asLeft).attempt.void
              }
            }.flatten
          }
        }

        def expire: Task[Unit] = state.update {
          case Value(_) => NoValue
          case NoValue => NoValue
          case st@Updating(_) => st
        }
      }
    }
  }

  it("fetch should only be called once") {
    val task = for {
      state <- Ref.of[Task, State](NoValue)
      cache = Cache(state, slowFetch)
      _ <- cache.get.start
      _ <- cache.get.start
    } yield ()

    task.runToFuture(testScheduler)
    testScheduler.tick(100.milliseconds)

    succeed
  }

  it("failing fast fetch should propagate errors") {
    val failingFetch = fastfetch.flatMap(_ => Task.raiseError(error))

    for {
      state <- Ref.of[Task, State](NoValue)
      cache = Cache(state, failingFetch)
      v1 <- cache.get.failed
    } yield {
      v1 shouldBe error
    }
  }

  // Interruptions may happen during any flat map or inside the fetch
  // we need to ensure we handle them:
  // - dispose of any resources
  // - restore inconsistent state
  it("calls complete with value when interrupted") {
    val task = for {
      state <- Ref.of[Task, State](NoValue)
      cache = Cache(state, slowFetch)
      _ <- cache.get
    } yield ()

    val future = task.runToFuture(testScheduler)
    testScheduler.tick(10.milliseconds)
    future.cancel()

    succeed
  }

}
