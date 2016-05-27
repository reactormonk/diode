package diode

import diode.util.RunAfter

import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

trait Effect[+A] {
  /**
    * Runs the effect and dispatches the result of the effect.
    *
    * @param dispatch Function to dispatch the effect result with.
    * @return A future that completes when the effect completes.
    */
  def run(dispatch: A => Unit): Future[Unit]

  /**
    * Combines two effects so that will be run in parallel.
    */
  def +[B >: A](that: Effect[B]): EffectSet[B]

  /**
    * Combines another effect with this one, to be run after this effect.
    */
  def >>[B >: A](that: Effect[B]): EffectSeq[B]

  /**
    * Combines another effect with this one, to be run before this effect.
    */
  def <<[B >: A](that: Effect[B]): EffectSeq[B]

  /**
    * Returns the number of effects
    */
  def size: Int

  /**
    * Runs the effect function and returns its value (a Future[List[A]])
    */
  def toFuture: Future[List[A]]

  /**
    * Delays the execution of this effect by duration `delay`
    */
  def after(delay: FiniteDuration)(implicit runner: RunAfter): Effect[A]

  /**
    * Creates a new effect by applying a function to the successful result of
    * this effect. If this effect is completed with an exception then the new
    * effect will also contain this exception.
    */
  def map[B](g: A => B): Effect[B]

  def ec: ExecutionContext
}

abstract class EffectBase[+A](val ec: ExecutionContext) extends Effect[A] {
  self =>
  override def +[B >: A](that: Effect[B]) = new EffectSet(this, Set(that), ec)

  override def >>[B >: A](that: Effect[B]) = new EffectSeq(this, List(that), ec)

  override def <<[B >: A](that: Effect[B]) = new EffectSeq(that, List(this), ec)

  override def size = 1

  override def after(delay: FiniteDuration)(implicit runner: RunAfter): Effect[A] = new DelayedEffect[A](runner, self, delay, ec)

}

class DelayedEffect[+A](runner: RunAfter, head: Effect[A], delay: FiniteDuration, ec: ExecutionContext) extends EffectBase[A](ec) {
  private def executeWith[B](f: Effect[A] => Future[B]): Future[B] =
    runner.runAfter(delay)(()).flatMap(_ => f(head))(ec)

  override def run(dispatch: A => Unit) =
    executeWith(_.run(dispatch))

  override def toFuture =
    executeWith(_.toFuture)

  override def map[B](g: A => B): Effect[B] =
    new DelayedEffect(runner, head.map(g), delay, ec)
}

/**
  * Wraps a function to be executed later. Function must return a `Future[A]` and the returned
  * action is automatically dispatched when `run` is called.
  *
  * @param f The effect function, returning a `Future[A]`
  */
class EffectSingle[+A](f: () => Future[A], ec: ExecutionContext) extends EffectBase[A](ec) {
  override def run(dispatch: A => Unit) = f().map(dispatch)(ec)

  override def toFuture: Future[List[A]] = f().map(a => List(a))(ec)

  override def map[B](g: A => B): Effect[B] =
    new EffectSingle(() => f().map(g)(ec), ec)
}

/**
  * Wraps multiple `Effects` to be executed later. Effects are executed in the order they appear and the
  * next effect is run only after the previous has completed. If an effect fails, the execution stops.
  *
  * @param head First effect to be run.
  * @param tail Rest of the effects.
  */
class EffectSeq[+A](head: Effect[A], tail: Seq[Effect[A]], ec: ExecutionContext) extends EffectBase[A](ec) {
  private def executeWith[B](f: Effect[A] => Future[B]): Future[B] =
    tail.foldLeft(f(head)) { (prev, effect) => prev.flatMap(_ => f(effect))(ec) }

  override def run(dispatch: A => Unit) =
    executeWith(_.run(dispatch))

  override def >>[B >: A](that: Effect[B]) =
    new EffectSeq(head, tail :+ that, ec)

  override def <<[B >: A](that: Effect[B]) =
    new EffectSeq(that, head +: tail, ec)

  override def size =
    head.size + tail.foldLeft(0)((acc, e) => acc + e.size)

  override def toFuture =
    executeWith(_.toFuture)

  override def map[B](g: A => B) =
    new EffectSeq(head.map(g), tail.map(_.map(g)), ec)
}

/**
  * Wraps multiple `Effects` to be executed later. Effects are executed in parallel without any ordering.
  *
  * @param head First effect to be run.
  * @param tail Rest of the effects.
  */
class EffectSet[+A](head: Effect[A], tail: Set[Effect[A]], override implicit val ec: ExecutionContext) extends EffectBase[A](ec) {
  private def executeWith[B](f: Effect[A] => Future[B]): Future[Set[B]] =
    Future.traverse(tail + head)(f(_))

  override def run(dispatch: A => Unit) =
    executeWith(_.run(x => dispatch(x))).map(_ => ())

  override def +[B >: A](that: Effect[B]) =
    new EffectSet(head, tail.asInstanceOf[Set[Effect[B]]] + that, ec)

  override def size =
    head.size + tail.foldLeft(0)((acc, e) => acc + e.size)

  override def toFuture: Future[List[A]] =
    executeWith(_.toFuture).map(_.flatten.toList)(ec)

  override def map[B](g: A => B) =
    new EffectSet(head.map(g), tail.map(_.map(g)), ec)
}

object Effect {
  type EffectF[A] = () => Future[A]

  def apply[A](f: => Future[A])(implicit ec: ExecutionContext): EffectSingle[A] =
    new EffectSingle(f _, ec)

  def apply[A](f: => Future[A], tail: EffectF[A]*)(implicit ec: ExecutionContext): EffectSet[A] =
    new EffectSet(new EffectSingle(f _, ec), tail.map[Effect[A], Seq[Effect[A]]](f => new EffectSingle(f, ec)).toSet, ec)

  /**
    * Converts a lazy action value into an effect. Typically used in combination with other effects or
    * with `after` to delay execution.
    */
  def action[A](action: => A)(implicit ec: ExecutionContext): EffectSingle[A] =
    new EffectSingle(() => Future.successful(action), ec)

  implicit def f2effect[A](f: EffectF[A])(implicit ec: ExecutionContext): EffectSingle[A] = new EffectSingle(f, ec)
}
