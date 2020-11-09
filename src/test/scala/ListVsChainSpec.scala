import cats.data.Chain
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.funspec.AsyncFunSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._
import cats.implicits._

class ListVsChainSpec extends AsyncFunSpecLike with Matchers {
  implicit val scheduler: Scheduler = Scheduler.Implicits.global

  describe("List") {
    val sentence1: List[String] = List("cats", "are", "cute")

    it("prepends") {

      val sentence2: List[String] = "tabby" :: sentence1

      println(s"original: $sentence1")
      println(s"modified: $sentence2")

      succeed
    }

    it("appends") {
      val sentence2: List[String] = sentence1 :+ "because they are fluffy"

      println(s"original: $sentence1")
      println(s"modified: $sentence2")

      succeed
    }
  }

  describe("chain") {
    val sentence1: Chain[String] = Chain("cats", "are", "cute")

    it("prepends") {
      val sentence2: Chain[String] = sentence1.prepend("tabby")

      println(s"original: $sentence1")
      println(s"modified: $sentence2")

      succeed
    }

    it("appends") {
      val sentence2: Chain[String] = sentence1.append("because they are fluffy")

      println(s"original: $sentence1")
      println(s"modified: $sentence2")

      succeed
    }
  }

  describe("benchmark") {
    it("append") {

      val measureRunTime = Observable.range(1, 40000) //generate data
        .map(Chain(_)) // map to our data structure
        .foldL // combining the lists together
        .timed // record how long the combining takes
        .map { case (duration, _) => duration.toMillis }
        .runToFuture

      val millis = Await.result(measureRunTime, 10.second)

      println(s"took: ${millis} ms")
      succeed
    }
  }
}
