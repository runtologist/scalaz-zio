package scalaz.zio.interop.reactiveStreams

import org.reactivestreams.{ Subscriber, Subscription }
import scalaz.zio.stream.ZStream
import scalaz.zio.stream.ZStream.Fold
import scalaz.zio.{ Promise, Queue, Runtime, Task, UIO, ZIO }

private[reactiveStreams] object QueueSubscriber {
  def make[A](capacity: Int): ZIO[Any, Nothing, (Subscriber[A], ZStream[Any, Throwable, A])] =
    for {
      runtime <- UIO.runtime[Any]
      q       <- Queue.bounded[A](capacity)
      sp      <- Promise.make[Nothing, Subscription]
      qs      = new QueueSubscriber[A](runtime, q, sp)
    } yield (qs, qs)
}

private class QueueSubscriber[A](runtime: Runtime[_], q: Queue[A], subscriptionP: Promise[Nothing, Subscription])
    extends Subscriber[A]
    with ZStream[Any, Throwable, A] {

  private[this] val capacity: Long = q.capacity.toLong

  @volatile private[this] var completed: Boolean        = false
  @volatile private[this] var failed: Option[Throwable] = None

  override def fold[R1 <: Any, E1 >: Throwable, A1 >: A, S]: Fold[R1, E1, A1, S] =
    subscriptionP.await.map { subscription => (s: S, cont: S => Boolean, f: (S, A1) => ZIO[R1, E1, S]) =>
      def loop(s: S, demand: Long): ZIO[R1, E1, S] =
        if (!cont(s)) UIO.succeed(s)
        else
          q.size.flatMap { n =>
            val empty = n <= 0
            if (empty && completed) UIO.succeed(s)
            else if (empty && failed.isDefined) Task.fail(failed.get)
            else if (empty && (demand < q.capacity)) UIO(subscription.request(capacity - demand)) *> loop(s, capacity)
            else q.take.flatMap(f(s, _)).flatMap(loop(_, demand - 1))
          } <> failed.fold[Task[S]](UIO.succeed(s))(Task.fail)
      loop(s, 0).ensuring(q.shutdown)
    }.onInterrupt(q.shutdown)

  override def onSubscribe(s: Subscription): Unit = {
    if (s == null) throw new NullPointerException("s was null in onSubscribe")
    runtime.unsafeRun(
      subscriptionP
        .succeed(s)
        .flatMap[Any, Nothing, Unit] {
          case true  => q.awaitShutdown.ensuring(UIO(s.cancel()).whenM(UIO(!completed && failed.isEmpty))).fork.void
          case false => UIO(s.cancel())
        }
    )
  }

  override def onNext(t: A): Unit = {
    if (t == null) throw new NullPointerException("t was null in onNext")
    runtime.unsafeRunSync(q.offer(t))
    ()
  }

  override def onError(e: Throwable): Unit = {
    if (e == null) throw new NullPointerException("t was null in onError")
    failed = Some(e)
    shutdownQueueIfEmpty()
  }

  override def onComplete(): Unit = {
    completed = true
    shutdownQueueIfEmpty()
  }

  private def shutdownQueueIfEmpty(): Unit = {
    runtime.unsafeRunSync(q.size.flatMap[Any, Nothing, Unit](n => if (n <= 0) q.shutdown else UIO.unit))
    ()
  }
}
