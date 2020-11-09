import java.time
import java.time.Instant

import Events._
import cats.effect.Clock
import monix.eval.Task
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import org.scalatest.funspec.AsyncFunSpecLike
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration._

/*
* 1. Depending on cats.effect Clock[T] instead of java.time Clock means:
*     a. getting current time is pure
*     b. because it is an interface, it lets you inject alternative implementations
*     https://typelevel.org/cats-effect/datatypes/clock.html
*
* 2. Inject a Clock[T] implementation that delegates to the scheduler means:
*     a. time-based logic can be tested much more deterministically
*     b. better test performance (without actual delays happening)
*     c. able to test the code correctly captures time and react to time-based events
 */

object Events {
  case class Event(id: String)
  case class ProcessedEvent(event: Event, start:Instant, finished: Instant)

  class EventProcessor(clock: Clock[Task]) {
    def run(event: Event): Task[ProcessedEvent] =
      for {
        start    <- clock.monotonic(MILLISECONDS)
        _        <- Task.sleep(1.minute)
        finished <- clock.monotonic(MILLISECONDS)
      } yield ProcessedEvent(
        event,
        start = Instant.ofEpochMilli(start),
        finished = Instant.ofEpochMilli(finished)
      )
  }
}

class TimedEventSpec extends AsyncFunSpecLike with Matchers {
  describe("Correctly capture processing time") {
    val events: Observable[Event] = Observable(Event("1"), Event("2"), Event("3"), Event("4"))
    val scheduler: TestScheduler = TestScheduler()

    it("use system clock") {
      val log = mutable.Queue.empty[String]
      def logProcessDuration(processedEvent: ProcessedEvent): Task[ProcessedEvent] = Task {
        val duration = durationBetween(processedEvent)
        log.enqueue(s"duration(${processedEvent.event.id}): ${duration.toMinutes}")

        processedEvent
      }

      val systemClock: Clock[Task] = new Clock[Task] {
        override def realTime(unit: TimeUnit): Task[Long] = Task(unit.convert(System.currentTimeMillis(), MILLISECONDS))
        override def monotonic(unit: TimeUnit): Task[Long] = Task(unit.convert(System.nanoTime(), NANOSECONDS))
      }

      val processor = new EventProcessor(systemClock)

      events
        .delayOnNext(2.minutes)
        .mapEval(processor.run)
        .mapEval(logProcessDuration)
        .completedL
        .runToFuture(scheduler)

      scheduler.tick(0.minutes)
      log.toList shouldBe List.empty[String]

      scheduler.tick(2.minutes)
      log.toList shouldBe List.empty[String]

      scheduler.tick(1.minutes)
      log.toList shouldBe List("duration(1): 1")

      // Expected :List("duration(1): 1")
      // Actual   :List("duration(1): 0")
      // Here 3 minutes has passed for the observable task.
      // However the system clock is still operating in real time
    }

    it("use scheduler clock") {
      val log = mutable.Queue.empty[String]
      def logProcessDuration(processedEvent: ProcessedEvent): Task[ProcessedEvent] = Task {
        val duration = durationBetween(processedEvent)
        log.enqueue(s"duration(${processedEvent.event.id}): ${duration.toMinutes}")

        processedEvent
      }

//      val systemClock: Clock[Task] = new Clock[Task] {
//        override def realTime(unit: TimeUnit): Task[Long] = Task(unit.convert(System.currentTimeMillis(), MILLISECONDS))
//        override def monotonic(unit: TimeUnit): Task[Long] = Task(unit.convert(System.nanoTime(), NANOSECONDS))
//      }
//
      val schedulerClock: Clock[Task] = new Clock[Task] {
        override def realTime(unit: TimeUnit): Task[Long] = Task(scheduler.clockRealTime(unit))
        override def monotonic(unit: TimeUnit): Task[Long] = Task(scheduler.clockMonotonic(unit))
      }

      val processor = new EventProcessor(schedulerClock)

      events
        .delayOnNext(2.minutes)
        .mapEval(processor.run)
        .mapEval(logProcessDuration)
        .completedL
        .runToFuture(scheduler)

      scheduler.tick(0.minutes) // No events, no logs
      log.toList shouldBe List.empty[String]

      scheduler.tick(2.minutes) // Event delay 2 minutes - not logs
      log.toList shouldBe List.empty[String]

      scheduler.tick(1.minutes)
      log.toList shouldBe List("duration(1): 1")

      scheduler.tick(2.minutes)
      log.toList shouldBe List("duration(1): 1")

      scheduler.tick(1.minutes)
      log.toList shouldBe List("duration(1): 1", "duration(2): 1")

      scheduler.tick(2.minutes)
      log.toList shouldBe List("duration(1): 1", "duration(2): 1")

      scheduler.tick(1.minutes)
      log.toList shouldBe List("duration(1): 1", "duration(2): 1", "duration(3): 1")

      scheduler.tick(2.minutes)
      log.toList shouldBe List("duration(1): 1", "duration(2): 1", "duration(3): 1")

      scheduler.tick(1.minutes)
      log.toList shouldBe List("duration(1): 1", "duration(2): 1", "duration(3): 1", "duration(4): 1")

      /* Summary
      * 1. Depending on cats.effect Clock[T] instead of java.time Clock means:
      *     a. getting current time is pure
      *     b. because it is an interface, it lets you inject alternative implementations
      *
      * 2. Inject a Clock[T] implementation that delegates to the scheduler means:
      *     a. time-based logic can be tested much more deterministically
      *     b. better test performance (without actual delays happening)
      *     c. able to test the code correctly captures time and react to time-based events
       */
    }
  }

  private def durationBetween(processedEvent: ProcessedEvent): FiniteDuration = {
    val duration = time.Duration.between(processedEvent.start, processedEvent.finished)
    FiniteDuration(duration.toNanos, NANOSECONDS)
  }
}
