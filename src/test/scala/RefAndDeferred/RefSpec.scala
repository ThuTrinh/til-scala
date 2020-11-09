package RefAndDeferred

import cats.effect.concurrent.Ref
import cats.implicits._
import monix.eval.Task
import scala.concurrent.duration._

class RefSpec extends BaseAsyncSpec {

  it("loses updates if only use Get and Set") {
    def report(trace: Ref[Task, List[String]], msg: String): Task[Unit] =
      for {
        t <- trace.get
          _ <- Task.unit.delayExecution(5.millisecond)
        _ <- trace.set(msg :: t)
      } yield ()

    for {
      trace <- Ref[Task].of(List.empty[String])
      _ <- report(trace, "one").start
      _ <- report(trace, "two").start
        _ <- Task.unit.delayExecution(10.millisecond)
      value <- trace.get
    } yield {
      value shouldBe List("two", "one")
    }
  }

  it("nondeterministic order if don't return result atomicly") {
    def sprinter(name: String, finishLine: Ref[Task, Int]): Task[String] =
      for {
        pos <- finishLine.modify(old => (old + 1, old + 1))
//        _ <- finishLine.update(_ + 1)
//        pos <- finishLine.get
        announcement <- Task.pure(s"$name arrived at position $pos")
      } yield announcement


    def sprint = Ref[Task].of(0).flatMap { finishLine =>
      List(
        sprinter("A", finishLine),
        sprinter("B", finishLine),
        sprinter("C", finishLine)
      ).parSequence
    }

    sprint.map { announcements =>
      announcements shouldBe List(
        s"A arrived at position 1",
        s"B arrived at position 2",
        s"C arrived at position 3"
      )
    }
  }
}
