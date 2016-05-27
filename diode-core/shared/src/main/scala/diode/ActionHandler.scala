package diode

import diode.util.RunAfter

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

/**
  * Base class for all action handlers.
  *
  * @param modelRW Model reader/writer for the actions this handler processes.
  */
abstract class ActionHandler[M, T, A](val modelRW: ModelRW[M, T]) {

  import ActionResult._

  private var currentModel: M = modelRW.root.value

  lazy val liftedHandler = handle.lift

  /**
    * Handles the incoming action by updating current model and calling the real `handle` function
    */
  def handleAction(model: M, action: A): Option[ActionResult[M, A]] = {
    currentModel = model
    liftedHandler(action)
  }

  /**
    * Override this function to handle dispatched actions.
    */
  protected def handle: PartialFunction[A, ActionResult[M, A]]

  /**
    * Helper function that returns the current value from the model.
    */
  def value: T = modelRW.eval(currentModel)

  /**
    * Helper function to create a `ModelUpdate` result from a new value.
    *
    * @param newValue
    * @return
    */
  def updated(newValue: T): ActionResult[M, A] =
    ModelUpdate(modelRW.updatedWith(currentModel, newValue))

  /**
    * Helper function to create a `ModelUpdateSilent` result from a new value. Being silent, the
    * update prevents any calls to listeners.
    *
    * @param newValue
    * @return
    */
  def updatedSilent(newValue: T): ActionResult[M, A] =
    ModelUpdateSilent(modelRW.updatedWith(currentModel, newValue))

  /**
    * Helper function to create a `ModelUpdateEffect` result from a new value and an effect.
    *
    * @param newValue
    * @param effect
    * @return
    */
  def updated(newValue: T, effect: Effect[A]): ActionResult[M, A] =
    ModelUpdateEffect(modelRW.updatedWith(currentModel, newValue), effect)

  /**
    * Helper function to create a `ModelUpdateSilentEffect` result from a new value and an effect. Being silent, the
    * update prevents any calls to listeners.
    *
    * @param newValue
    * @param effect
    * @return
    */
  def updatedSilent(newValue: T, effect: Effect[A]): ActionResult[M, A] =
    ModelUpdateSilentEffect(modelRW.updatedWith(currentModel, newValue), effect)

  /**
    * Helper function when the action does no model changes or effects.
    *
    * @return
    */
  def noChange: ActionResult[M, A] =
    NoChange

  /**
    * Helper function to create an `EffectOnly` result with the provided effect.
    *
    * @param effect
    * @return
    */
  def effectOnly(effect: Effect[A]): ActionResult[M, A] =
    EffectOnly(effect)

  /**
    * Helper function to create a delayed effect.
    *
    * @param delay How much to delay the effect.
    * @param f     Result of the effect
    */
  def runAfter(delay: FiniteDuration)(f: => A)(implicit runner: RunAfter, ec: ExecutionContext): Effect[A] =
    Effect(runner.runAfter(delay)(f))
}

object ActionHandler {
  implicit def extractHandler[M, A](actionHandler: ActionHandler[M, _, A]): (M, A) => Option[ActionResult[M, A]] =
    actionHandler.handleAction
}
