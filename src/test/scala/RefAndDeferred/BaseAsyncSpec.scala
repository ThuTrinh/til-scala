package RefAndDeferred

import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.funspec.AsyncFunSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

trait BaseAsyncSpec extends AsyncFunSpecLike with Matchers {
  implicit val scheduler: Scheduler = Scheduler.Implicits.global

  implicit def taskToFuture[T](task: Task[T])(implicit scheduler: Scheduler): Future[T] = task.runToFuture

  def await[T](future: Future[T]): T = Await.result(future, Duration.Inf)
}

