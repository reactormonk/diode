package diode

import scala.collection.immutable.Queue
import scala.language.higherKinds

trait Dispatcher[-A] {
  def dispatch(action: DiodeAction[A]): Unit
  def dispatch(action: A): Unit = dispatch(Action(action))

  def apply(action: A) = dispatch(action)
}

trait ActionProcessor[M <: AnyRef, A] {
  def process(dispatch: Dispatcher[A], action: A, next: A => ActionResult[M, A], currentModel: M): ActionResult[M, A]
}

sealed trait ActionResult[+M, +A] {
  def newModelOpt: Option[M] = None
  def effectOpt: Option[Effect[A]] = None
}

sealed trait ModelUpdated[+M, A] extends ActionResult[M, A] {
  def newModel: M
  override def newModelOpt: Option[M] = Some(newModel)
}

sealed trait HasEffect[+M, A] extends ActionResult[M, A] {
  def effect: Effect[A]
  override def effectOpt: Option[Effect[A]] = Some(effect)
}

sealed trait UpdateSilent

object ActionResult {

  case object NoChange extends ActionResult[Nothing, Nothing]

  final case class ModelUpdate[M, A](newModel: M) extends ModelUpdated[M, A]

  final case class ModelUpdateSilent[M, A](newModel: M) extends ModelUpdated[M, A] with UpdateSilent

  final case class EffectOnly[A](effect: Effect[A]) extends ActionResult[Nothing, A] with HasEffect[Nothing, A]

  final case class ModelUpdateEffect[M, A](newModel: M, effect: Effect[A]) extends ModelUpdated[M, A] with HasEffect[M, A]

  final case class ModelUpdateSilentEffect[M, A](newModel: M, effect: Effect[A])
    extends ModelUpdated[M, A] with HasEffect[M, A] with UpdateSilent

  def apply[M, A](model: Option[M], effect: Option[Effect[A]]): ActionResult[M, A] = (model, effect) match {
    case (Some(m), Some(e)) => ModelUpdateEffect(m, e)
    case (Some(m), None) => ModelUpdate(m)
    case (None, Some(e)) => EffectOnly(e)
    case _ => NoChange
  }
}

sealed trait DiodeAction[+A]
case class Action[+A](a: A) extends DiodeAction[A]
case class ActionSeq[+A](a: Seq[A]) extends DiodeAction[A]
case object Noop extends DiodeAction[Nothing]

trait Circuit[M <: AnyRef, A] extends Dispatcher[A] {

  type HandlerFunction = (M, A) => Option[ActionResult[M, A]]

  private case class Subscription[T](listener: ModelR[M, T] => Unit, cursor: ModelR[M, T], lastValue: T) {
    def changed: Option[Subscription[T]] = {
      if (cursor === lastValue)
        None
      else
        Some(copy(lastValue = cursor.eval(model)))
    }

    def call(): Unit = listener(cursor)
  }

  private[diode] var model: M = initialModel

  /**
    * Provides the initial value for the model
    */
  protected def initialModel: M

  /**
    * Handles all dispatched actions
    *
    * @return
    */
  protected def actionHandler: HandlerFunction

  private val modelRW = new RootModelRW[M](model)
  private var isDispatching = false
  private var dispatchQueue = Queue.empty[DiodeAction[A]]
  private var listenerId = 0
  private var listeners = Map.empty[Int, Subscription[_]]
  private var processors = List.empty[ActionProcessor[M, A]]
  private var processChain: A => ActionResult[M, A] = buildProcessChain

  private def buildProcessChain = {
    // chain processing functions
    processors.reverse.foldLeft((x: A) => process(Action(x)))((next, processor) =>
      (action: A) => processor.process(this, action, next, model)
    )
  }

  /**
    * Zoom into the model using the `get` function
    *
    * @param get Function that returns the part of the model you are interested in
    * @return A `ModelR[T]` giving you read-only access to part of the model
    */
  def zoom[T](get: M => T)(implicit feq: FastEq[_ >: T]): ModelR[M, T] =
    modelRW.zoom[T](get)

  def zoomMap[F[_], A, B](fa: M => F[A])(f: A => B)
    (implicit monad: Monad[F], feq: FastEq[_ >: B]): ModelR[M, F[B]] =
    modelRW.zoomMap(fa)(f)

  def zoomFlatMap[F[_], A, B](fa: M => F[A])(f: A => F[B])
    (implicit monad: Monad[F], feq: FastEq[_ >: B]): ModelR[M, F[B]] =
    modelRW.zoomFlatMap(fa)(f)

  /**
    * Zoom into the model using `get` and `set` functions
    *
    * @param get Function that returns the part of the model you are interested in
    * @param set Function that updates the part of the model you are interested in
    * @return A `ModelRW[T]` giving you read/update access to part of the model
    */
  def zoomRW[T](get: M => T)(set: (M, T) => M)(implicit feq: FastEq[_ >: T]): ModelRW[M, T] = modelRW.zoomRW(get)(set)

  def zoomMapRW[F[_], A, B](fa: M => F[A])(f: A => B)(set: (M, F[B]) => M)
    (implicit monad: Monad[F], feq: FastEq[_ >: B]): ModelRW[M, F[B]] =
    modelRW.zoomMapRW(fa)(f)(set)

  def zoomFlatMapRW[F[_], A, B](fa: M => F[A])(f: A => F[B])(set: (M, F[B]) => M)
    (implicit monad: Monad[F], feq: FastEq[_ >: B]): ModelRW[M, F[B]] =
    modelRW.zoomFlatMapRW(fa)(f)(set)

  /**
    * Subscribes to listen to changes in the model. By providing a `cursor` you can limit
    * what part of the model must change for your listener to be called. If omitted, all changes
    * result in a call.
    *
    * @param cursor   Model reader returning the part of the model you are interested in.
    * @param listener Function to be called when model is updated. The listener function gets
    *                 the model reader as a parameter.
    * @return A function to unsubscribe your listener
    */
  def subscribe[T](cursor: ModelR[M, T])(listener: ModelR[M, T] => Unit): () => Unit = {
    this.synchronized {
      listenerId += 1
      val id = listenerId
      listeners += id -> Subscription(listener, cursor, cursor.eval(model))
      () => this.synchronized(listeners -= id)
    }
  }

  /**
    * Adds a new `ActionProcessor[M, A]` to the action processing chain. The processor is called for
    * every dispatched action.
    *
    * @param processor
    */
  def addProcessor(processor: ActionProcessor[M, A]): Unit = {
    this.synchronized {
      processors = processor :: processors
      processChain = buildProcessChain
    }
  }

  /**
    * Removes a previously added `ActionProcessor[M, A]` from the action processing chain.
    *
    * @param processor
    */
  def removeProcessor(processor: ActionProcessor[M, A]): Unit = {
    this.synchronized {
      processors = processors.filterNot(_ == processor)
      processChain = buildProcessChain
    }
  }

  /**
    * Handle a fatal error. Override this function to do something with exceptions that
    * occur while dispatching actions.
    *
    * @param action Action that caused the exception
    * @param e      Exception that was thrown
    */
  def handleFatal(action: A, e: Throwable): Unit = throw e

  /**
    * Handle a non-fatal error, such as dispatching an action with no action handler.
    *
    * @param msg Error message
    */
  def handleError(msg: String): Nothing = throw new Exception(s"handleError called with: $msg")

  /**
    * Updates the model if it has changed (reference equality check)
    */
  private def update(newModel: M) = {
    if (newModel ne model) {
      model = newModel
    }
  }

  /**
    * The final action processor that does actual action handling.
    *
    * @param action Action to be handled
    * @return
    */
  private def process(diodeAction: DiodeAction[A]): ActionResult[M, A] = {
    multiActionHandler(diodeAction, action =>
      actionHandler(model, action).getOrElse(handleError(s"Action was not processed: $action")),
      ActionResult.NoChange
    )
  }

  private def multiActionHandler[B](action: DiodeAction[A], normalDispatch: A => B, default: B): B = action match {
    case Action(a) => normalDispatch(a)
    case ActionSeq(seq) =>
      // dispatch all actions in the sequence using internal dispatchBase to prevent
      // additional calls to subscribed listeners
      seq.foreach(dispatchBase)
      default
    case Noop =>
      // ignore
      default
  }


  /**
    * Composes multiple handlers into a single handler. Processing stops as soon as a handler is able to handle
    * the action. If none of them handle the action, `None` is returned
    */
  def composeHandlers(handlers: HandlerFunction*): HandlerFunction =
    (model, action) => {
      handlers.foldLeft(Option.empty[ActionResult[M, A]]) {
        (a, b) => a.orElse(b(model, action))
      }
    }

  @deprecated("Use composeHandlers or foldHandlers instead", "0.5.1")
  def combineHandlers(handlers: HandlerFunction*): HandlerFunction = composeHandlers(handlers: _*)

  /**
    * Folds multiple handlers into a single function so that each handler is called
    * in turn and an updated model is passed on to the next handler. Returned `ActionResult` contains the final model
    * and combined effects.
    */
  def foldHandlers(handlers: HandlerFunction*): HandlerFunction =
    (initialModel, action) => {
      handlers.foldLeft((initialModel, Option.empty[ActionResult[M, A]])) { case ((currentModel, currentResult), handler) =>
        handler(currentModel, action) match {
          case None =>
            (currentModel, currentResult)
          case Some(result) =>
            val (nextModel, nextResult) = currentResult match {
              case Some(cr) =>
                val newEffect = (cr.effectOpt, result.effectOpt) match {
                  case (Some(e1), Some(e2)) => Some(e1 + e2)
                  case (Some(e1), None) => Some(e1)
                  case (None, Some(e2)) => Some(e2)
                  case (None, None) => None
                }
                val newModel = result.newModelOpt.orElse(cr.newModelOpt)
                (newModel.getOrElse(currentModel), ActionResult(newModel, newEffect))
              case None =>
                (result.newModelOpt.getOrElse(currentModel), result)
            }
            (nextModel, Some(nextResult))
        }
      }._2
    }


  /**
    * Dispatch the action, call change listeners when completed
    *
    * @param action Action to dispatch
    */
  def dispatch(diodeAction: DiodeAction[A]): Unit = {
    this.synchronized {
      if (!isDispatching) {
        multiActionHandler[Unit](diodeAction, {action =>
          try {
            isDispatching = true
            val oldModel = model
            val silent = dispatchBase(action)
            if (oldModel ne model) {
              // walk through all listeners and update subscriptions when model has changed
              val updated = listeners.foldLeft(listeners) { case (l, (key, sub)) =>
                if (listeners.isDefinedAt(key)) {
                  // Listener still exists
                  sub.changed match {
                    case Some(newSub) => {
                      // value at the cursor has changed, call listener and update subscription
                      if (!silent) sub.call()
                      l.updated(key, newSub)
                    }

                    case None => {
                      // nothing interesting happened
                      l
                    }
                  }
                }
                else {
                  // Listener was removed since we started
                  l
                }
              }

              // Listeners may have changed during processing (subscribe or unsubscribe)
              // so only update the listeners that are still there, and leave any new listeners that may be there now.
              listeners = updated.foldLeft(listeners) { case (l, (key, sub)) =>
                if (l.isDefinedAt(key)) {
                  // Listener still exists for this key
                  l.updated(key, sub)
                }
                else {
                  // Listener was removed for this key, skip it
                  l
                }
              }
            }
          } catch {
            case e: Throwable =>
              handleFatal(action, e)
          } finally {
            isDispatching = false
          }
          // if there is an item in the queue, dispatch it
          dispatchQueue.dequeueOption foreach { case (nextAction, queue) =>
            dispatchQueue = queue
            dispatch(nextAction)
          }
      }, ())
      } else {
        // add to the queue
        dispatchQueue = dispatchQueue.enqueue(diodeAction)
      }
    }
  }

  /**
    * Perform actual dispatching, without calling change listeners
    */
  protected def dispatchBase(action: A): Boolean = {
    try {
      val res: ActionResult[M, A] = processChain(action)
      res match {
        case ActionResult.NoChange =>
          // no-op
          false
        case ActionResult.ModelUpdate(newModel) =>
          update(newModel)
          false
        case ActionResult.ModelUpdateSilent(newModel) =>
          update(newModel)
          true
        case ActionResult.EffectOnly(effects) =>
          // run effects
          effects.run(dispatch).recover {
            case e: Throwable =>
              handleError(s"Error in processing effects for action $action: $e")
          }(effects.ec)
          true
        case ActionResult.ModelUpdateEffect(newModel, effects) =>
          val e: Effect[A] = effects
          update(newModel)
          // run effects
          e.run(dispatch).recover {
            case e: Throwable =>
              handleError(s"Error in processing effects for action $action: $e")
          }(effects.ec)
          false
        case ActionResult.ModelUpdateSilentEffect(newModel, effects) =>
          val e: Effect[A] = effects
          update(newModel)
          // run effects
          e.run(dispatch).recover {
            case e: Throwable =>
              handleError(s"Error in processing effects for action $action: $e")
          }(effects.ec)
          true
      }
    } catch {
      case e: Throwable =>
        handleFatal(action, e)
        true
    }
  }
}
