/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.AkkaException
import akka.actor.Actor.spawn
import akka.actor.{Actor, ErrorHandler, ErrorHandlerEvent}
import akka.routing.Dispatcher
import akka.japi.Procedure

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent. {ConcurrentLinkedQueue, TimeUnit}
import java.util.concurrent.TimeUnit.{NANOSECONDS => NANOS, MILLISECONDS => MILLIS}
import java.util.concurrent.atomic. {AtomicBoolean, AtomicInteger}
import annotation.tailrec

class FutureTimeoutException(message: String) extends AkkaException(message)

object Futures {

  /**
   * Module with utility methods for working with Futures.
   * <pre>
   * val future = Futures.future(1000) {
   *  ... // do stuff
   * }
   * </pre>
   */
   def future[T](timeout: Long,
                 dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher)
                (body: => T): Future[T] = {
    val f = new DefaultCompletableFuture[T](timeout)
    spawn({
      try { 
        f completeWithResult body 
      } catch { 
        case e => 
          ErrorHandler notifyListeners ErrorHandlerEvent(e, this)
          f completeWithException e
      }
    })(dispatcher)
    f
  }

  /**
   * (Blocking!)
   */
  def awaitAll(futures: List[Future[_]]): Unit = futures.foreach(_.await)

  /**
   *  Returns the First Future that is completed (blocking!)
   */
  def awaitOne(futures: List[Future[_]], timeout: Long = Long.MaxValue): Future[_] = firstCompletedOf(futures, timeout).await

  /**
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf(futures: Iterable[Future[_]], timeout: Long = Long.MaxValue): Future[_] = {
    val futureResult = new DefaultCompletableFuture[Any](timeout)

    val completeFirst: Future[_] => Unit = f => futureResult.completeWith(f.asInstanceOf[Future[Any]])
    for(f <- futures) f onComplete completeFirst

    futureResult
  }

  /**
   * Applies the supplied function to the specified collection of Futures after awaiting each future to be completed
   */
  def awaitMap[A,B](in: Traversable[Future[A]])(fun: (Future[A]) => B): Traversable[B] =
    in map { f => fun(f.await) }

  /**
   * Returns Future.resultOrException of the first completed of the 2 Futures provided (blocking!)
   */
  def awaitEither[T](f1: Future[T], f2: Future[T]): Option[T] = awaitOne(List(f1,f2)).asInstanceOf[Future[T]].resultOrException

  /**
   * A non-blocking fold over the specified futures.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   */
  def fold[T,R](zero: R, timeout: Long = Actor.TIMEOUT)(futures: Iterable[Future[T]])(foldFun: (R, T) => R): Future[R] = {
    if(futures.isEmpty) {
      (new DefaultCompletableFuture[R](timeout)) completeWithResult zero
    } else {
      val result = new DefaultCompletableFuture[R](timeout)
      val results = new ConcurrentLinkedQueue[T]()
      val waitingFor = new AtomicInteger(futures.size)

      val aggregate: Future[T] => Unit = f => if (!result.isCompleted) { //TODO: This is an optimization, is it premature?
        if (f.exception.isDefined)
          result completeWithException f.exception.get
        else {
          results add f.result.get
          if (waitingFor.decrementAndGet == 0) { //Only one thread can get here
            try {
              val r = scala.collection.JavaConversions.asScalaIterable(results).foldLeft(zero)(foldFun)
              results.clear //Do not retain the values since someone can hold onto the Future for a long time
              result completeWithResult r
            } catch {
              case e: Exception => 
                ErrorHandler notifyListeners ErrorHandlerEvent(e, this)
                result completeWithException e
            }
          }
        }
      }

      futures foreach { _ onComplete aggregate }
      result
    }
  }

  /**
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   */
  def reduce[T, R >: T](futures: Iterable[Future[T]], timeout: Long = Actor.TIMEOUT)(op: (R,T) => T): Future[R] = {
    if (futures.isEmpty)
      (new DefaultCompletableFuture[R](timeout)).completeWithException(new UnsupportedOperationException("empty reduce left"))
    else {
      val result = new DefaultCompletableFuture[R](timeout)
      val seedFound = new AtomicBoolean(false)
      val seedFold: Future[T] => Unit = f => {
        if (seedFound.compareAndSet(false, true)){ //Only the first completed should trigger the fold
          if (f.exception.isDefined) result completeWithException f.exception.get //If the seed is a failure, we're done here
          else (fold[T,R](f.result.get, timeout)(futures.filterNot(_ eq f))(op)).onComplete(result.completeWith(_)) //Fold using the seed
        }
        () //Without this Unit value, the compiler crashes
      }
      for(f <- futures) f onComplete seedFold //Attach the listener to the Futures
      result
    }
  }

  import scala.collection.mutable.Builder
  import scala.collection.generic.CanBuildFrom

  def sequence[A, M[_] <: Traversable[_]](in: M[Future[A]], timeout: Long = Actor.TIMEOUT)(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]]): Future[M[A]] =
    in.foldLeft(new DefaultCompletableFuture[Builder[A, M[A]]](timeout).completeWithResult(cbf(in)): Future[Builder[A, M[A]]])((fr, fa) => for (r <- fr; a <- fa.asInstanceOf[Future[A]]) yield (r += a)).map(_.result)

  def traverse[A, B, M[_] <: Traversable[_]](in: M[A], timeout: Long = Actor.TIMEOUT)(fn: A => Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Future[M[B]] =
    in.foldLeft(new DefaultCompletableFuture[Builder[B, M[B]]](timeout).completeWithResult(cbf(in)): Future[Builder[B, M[B]]]) { (fr, a) =>
      val fb = fn(a.asInstanceOf[A])
      for (r <- fr; b <-fb) yield (r += b)
    }.map(_.result)
}

sealed trait Future[T] {
  /**
   * Blocks the current thread until the Future has been completed or the
   * timeout has expired. In the case of the timeout expiring a
   * FutureTimeoutException will be thrown.
   */
  def await : Future[T]

  /**
   * Blocks the current thread until the Future has been completed. Use
   * caution with this method as it ignores the timeout and will block
   * indefinitely if the Future is never completed.
   */
  def awaitBlocking : Future[T]

  /**
   * Tests whether this Future has been completed.
   */
  final def isCompleted: Boolean = value.isDefined

  /**
   * Tests whether this Future's timeout has expired.
   *
   * Note that an expired Future may still contain a value, or it may be
   * completed with a value.
   */
  def isExpired: Boolean

  /**
   * This Future's timeout in nanoseconds.
   */
  def timeoutInNanos: Long

  /**
   * The contained value of this Future. Before this Future is completed
   * the value will be None. After completion the value will be Some(Right(t))
   * if it contains a valid result, or Some(Left(error)) if it contains
   * an exception.
   */
  def value: Option[Either[Throwable, T]]

  /**
   * Returns the successful result of this Future if it exists.
   */
  final def result: Option[T] = {
    val v = value
    if (v.isDefined) v.get.right.toOption
    else None
  }

  /**
   * Waits for the completion of this Future, then returns the completed value.
   * If the Future's timeout expires while waiting a FutureTimeoutException
   * will be thrown.
   *
   * Equivalent to calling future.await.value.
   */
  def awaitResult: Option[Either[Throwable, T]]

  /**
   * Returns the result of the Future if one is available within the specified
   * time, if the time left on the future is less than the specified time, the
   * time left on the future will be used instead of the specified time.
   * returns None if no result, Some(Right(t)) if a result, or
   * Some(Left(error)) if there was an exception
   */
  def resultWithin(time: Long, unit: TimeUnit): Option[Either[Throwable, T]]

  /**
   * Returns the contained exception of this Future if it exists.
   */
  final def exception: Option[Throwable] = {
    val v = value
    if (v.isDefined) v.get.left.toOption
    else None
  }

  /**
   * When this Future is completed, apply the provided function to the
   * Future. If the Future has already been completed, this will apply
   * immediatly.
   */
  def onComplete(func: Future[T] => Unit): Future[T]

  /**
   * When the future is compeleted with a valid result, apply the provided
   * PartialFunction to the result.
   */
  final def receive(pf: PartialFunction[Any, Unit]): Future[T] = onComplete { f =>
    val optr = f.result
    if (optr.isDefined) {
      val r = optr.get
      if (pf.isDefinedAt(r)) pf(r)
    }
  }

  /**
   * Creates a new Future by applying a function to the successful result of
   * this Future. If this Future is completed with an exception then the new
   * Future will also contain this exception.
   */
  final def map[A](f: T => A): Future[A] = {
    val fa = new DefaultCompletableFuture[A](timeoutInNanos, NANOS)
    onComplete { ft =>
      val optv = ft.value
      if (optv.isDefined) {
        val v = optv.get
        if (v.isLeft)
          fa complete v.asInstanceOf[Either[Throwable, A]]
        else {
          fa complete (try {
            Right(f(v.right.get))
          } catch {
            case e => 
              ErrorHandler notifyListeners ErrorHandlerEvent(e, this)
              Left(e)
          })
        }
      }
    }
    fa
  }

  /**
   * Creates a new Future by applying a function to the successful result of
   * this Future, and returns the result of the function as the new Future.
   * If this Future is completed with an exception then the new Future will
   * also contain this exception.
   */
  final def flatMap[A](f: T => Future[A]): Future[A] = {
    val fa = new DefaultCompletableFuture[A](timeoutInNanos, NANOS)
    onComplete { ft =>
      val optv = ft.value
      if (optv.isDefined) {
        val v = optv.get
        if (v.isLeft)
          fa complete v.asInstanceOf[Either[Throwable, A]]
        else {
          try {
            f(v.right.get) onComplete (fa.completeWith(_))
          } catch {
            case e => 
              ErrorHandler notifyListeners ErrorHandlerEvent(e, this)
              fa completeWithException e
          }
        }
      }
    }
    fa
  }

  final def foreach(f: T => Unit): Unit = onComplete { ft =>
    val optr = ft.result
    if (optr.isDefined)
      f(optr.get)
  }

  final def filter(p: T => Boolean): Future[T] = {
    val f = new DefaultCompletableFuture[T](timeoutInNanos, NANOS)
    onComplete { ft =>
      val optv = ft.value
      if (optv.isDefined) {
        val v = optv.get
        if (v.isLeft)
          f complete v
        else {
          val r = v.right.get
          f complete (try {
            if (p(r)) Right(r)
            else Left(new MatchError(r))
          } catch {
            case e => 
              ErrorHandler notifyListeners ErrorHandlerEvent(e, this)
              Left(e)
          })
        }
      }
    }
    f
  }

  /**
   *   Returns the current result, throws the exception is one has been raised, else returns None
   */
  final def resultOrException: Option[T] = {
    val v = value
    if (v.isDefined) {
      val r = v.get
      if (r.isLeft) throw r.left.get
      else r.right.toOption
    } else None
  }

  /* Java API */
  final def onComplete(proc: Procedure[Future[T]]): Future[T] = onComplete(proc(_))

}

trait CompletableFuture[T] extends Future[T] {
  def complete(value: Either[Throwable, T]): CompletableFuture[T]
  final def completeWithResult(result: T): CompletableFuture[T] = complete(Right(result))
  final def completeWithException(exception: Throwable): CompletableFuture[T] = complete(Left(exception))
  final def completeWith(other: Future[T]): CompletableFuture[T] = {
    val v = other.value
    if (v.isDefined) complete(v.get)
    else this
  }
}

// Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
class DefaultCompletableFuture[T](timeout: Long, timeunit: TimeUnit) extends CompletableFuture[T] {

  def this() = this(0, MILLIS)

  def this(timeout: Long) = this(timeout, MILLIS)

  val timeoutInNanos = timeunit.toNanos(timeout)
  private val _startTimeInNanos = currentTimeInNanos
  private val _lock = new ReentrantLock
  private val _signal = _lock.newCondition
  private var _value: Option[Either[Throwable, T]] = None
  private var _listeners: List[Future[T] => Unit] = Nil

  @tailrec
  private def awaitUnsafe(wait: Long): Boolean = {
    if (_value.isEmpty && wait > 0) {
      val start = currentTimeInNanos
      awaitUnsafe(try {
        _signal.awaitNanos(wait)
      } catch {
        case e: InterruptedException =>
          wait - (currentTimeInNanos - start)
      })
    } else {
      _value.isDefined
    }
  }

  def awaitResult: Option[Either[Throwable, T]] = try {
    _lock.lock
    awaitUnsafe(timeoutInNanos - (currentTimeInNanos - _startTimeInNanos))
    _value
  } finally {
    _lock.unlock
  }

  def resultWithin(time: Long, unit: TimeUnit): Option[Either[Throwable, T]] = try {
    _lock.lock
    awaitUnsafe(unit.toNanos(time).min(timeoutInNanos - (currentTimeInNanos - _startTimeInNanos)))
    _value
  } finally {
    _lock.unlock
  }

  def await = try {
    _lock.lock
    if (awaitUnsafe(timeoutInNanos - (currentTimeInNanos - _startTimeInNanos)))
      this
    else
      throw new FutureTimeoutException("Futures timed out after [" + NANOS.toMillis(timeoutInNanos) + "] milliseconds")
  } finally {
    _lock.unlock
  }

  def awaitBlocking = try {
    _lock.lock
    while (_value.isEmpty) {
      _signal.await
    }
    this
  } finally {
    _lock.unlock
  }

  def isExpired: Boolean = timeoutInNanos - (currentTimeInNanos - _startTimeInNanos) <= 0

  def value: Option[Either[Throwable, T]] = try {
    _lock.lock
    _value
  } finally {
    _lock.unlock
  }

  def complete(value: Either[Throwable, T]): DefaultCompletableFuture[T] = {
    val notifyTheseListeners = try {
      _lock.lock
      if (_value.isEmpty) {
        _value = Some(value)
        val all = _listeners
        _listeners = Nil
        all
      } else Nil
    } finally {
      _signal.signalAll
      _lock.unlock
    }

    if (notifyTheseListeners.nonEmpty)
      notifyTheseListeners foreach notify

    this
  }

  def onComplete(func: Future[T] => Unit): CompletableFuture[T] = {
    if (try {
      _lock.lock
      if (_value.isEmpty) {
        _listeners ::= func
        false
      }
      else true
    } finally {
      _lock.unlock
    }) notify(func)
    this
  }

  private def notify(func: Future[T] => Unit) {
    func(this)
  }

  private def currentTimeInNanos: Long = MILLIS.toNanos(System.currentTimeMillis)
}
