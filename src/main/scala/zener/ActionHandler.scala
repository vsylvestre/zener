package zener

import monix.eval.Task
import monix.reactive.Observable
import monix.reactive.subjects.Var

/**
  * A part of the application model.
  *
  * The [[ActionHandler]] usually only takes care of a single
  * field in the application model. Therefore, we want to be able
  * to concentrate on that very part of the model; but for that,
  * we need methods to convert the model to the reduced version
  * of the model, and vice versa.
  *
  * @param get Method to convert the model into a reduced version
  *            of the model
  * @param set Method to convert the reduced version of the model
  *            back into a complete model
  * @tparam M The complete model
  * @tparam Z The reduced model
  */
class ReducedModel[M, Z](val get: M => Z, val set: (M, Z) => M)

object ReducedModel {
  /**
    * Given that we're looking to represent part of the model,
    * and that the model is an Observable, it is required that
    * we convert it into our [[ReducedModel]] type.
    *
    * @param obs Observable to convert (stems from the model)
    * @param get The ``forward conversion`` method
    * @param set The ``backwards conversion`` method
    */
  implicit def observableToReducedModel[M, Z](obs: Observable[Z])(implicit get: M => Z, set: (M, Z) => M): ReducedModel[M, Z] =
    new ReducedModel[M, Z](get, set)
}

/**
  * The action handler.
  *
  * Handlers are defined for the [[Circuit]] to gather and
  * consume. They define how actions should be handled for
  * a given part of the model (see [[ReducedModel]]).
  *
  * @param rm A part of the model
  * @tparam M Type of the complete model
  * @tparam Z Type of the reduced model (usually a [[Particle]])
  */
abstract class ActionHandler[M, Z](rm: ReducedModel[M, Z]) {

  // Synchronized value of the observable model for use within the handler
  private var currentModel: Var[M] = _

  /**
    * Current value for the ``reduced`` model
    */
  final def value: Z = rm.get(currentModel())

  /**
    * Pending value for the reduced model
    *
    * This value is used when a [[load]] request is made
    * on this part of the model.
    *
    * @note Use with [[Particle]]: This value is most useful
    *       when used in pair with a [[Particle]]. We can
    *       transform the Particle into a ``pending`` one,
    *       which will easily translate into a pending view
    *       with the use of the [[ParticleBasedComponent]].
    *       Therefore, the default use with a [[Particle]] is
    *       already taken care of by this method, and need not
    *       be implemented in the Handler (unless providing
    *       a custom definition).
    */
  protected def pending: Option[Z] = value match {
    case x: Particle[_] => Some(x.pending.asInstanceOf[Z])
    case _ => None
  }

  /**
    * Load a new state in the application model, based
    * on the result of a task.
    *
    * Use this method when not concerned about the ``previous``
    * state of the model; otherwise, see [[update]].
    *
    * @param t Task to perform, the result of which will be
    *          loaded into the application state
    */
  final def load(t: Task[Z]): LoadTask[M] =
    new LoadTask[M] {
      override def whileLoading: Option[M] = pending.map(rm.set(currentModel(), _))
      override def task: Task[M] = t.map(rm.set(currentModel(), _))
    }

  /**
    * Update the state using a task based on the previous
    * state of the application model.
    *
    * Use this method when performing a task that alters the
    * current state of the model; otherwise, see [[load]].
    *
    * @param taskFn Task to perform, which takes the previous
    *               model as an input
    */
  final def update(taskFn: Z => Task[Z]): UpdateTask[M] =
    new UpdateTask[M] {
      override def task: Task[M] = taskFn(value).map(rm.set(currentModel(), _))
    }

  /**
    * When an action is required within the application, but
    * the action does not need that a state update be
    * triggered.
    *
    * Examples are VDom updates, notification handlers,
    * or file download tasks.
    *
    * @return A Monix Task containing the original value
    *         for the current model
    */
  final def noChange(t: Task[_]): NoChangeTask[M] =
    new NoChangeTask[M] {
      override def task: Task[M] = t.map(_ => currentModel())
    }

  /**
    * Partial function defining how actions should
    * be handled for this specific part of the model.
    *
    * Example use:
    *
    * {{{
    *   def handle: PartialFunction[Action, Task[M]] = {
    *     case Fetch => Task { ... }
    *     case Update => Task { ... }
    *     case Delete => Task { ... }
    *   }
    * }}}
    */
  protected def handle: PartialFunction[Action, CircuitTask[M]]

  /**
    * Executes the given action for the [[Circuit]] to consider.
    * That is, the local copy of the model is updated, and the
    * PartialFunction is converted into a ``readable`` type
    * for the [[Circuit]] to execute if needed.
    *
    * @param modelRef Reference to the observable model
    * @param action The action to be performed
    */
  private def execute(modelRef: Var[M], action: Action): Option[CircuitTask[M]] = {
    currentModel = modelRef
    if (action.conditionalTo) handle.lift(action) else None
  }
}

object ActionHandler {

  implicit def extractHandler[M <: AnyRef, Z](actionHandler: ActionHandler[M, Z]): (Var[M], Action) => Option[CircuitTask[M]] =
    actionHandler.execute
}
