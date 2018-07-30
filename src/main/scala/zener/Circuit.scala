package zener

import japgolly.scalajs.react.Callback
import monix.eval.Task
import monix.execution.{Ack, Cancelable}
import monix.execution.Ack.Continue
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import monix.reactive.subjects.Var

import scala.concurrent.duration._

/**
  * The `Circuit` type which manages modifications to the application
  * state.
  *
  * The Circuit is at the center of all state management operations.
  * A dispatched [[Action]] is received by the Circuit and sent to the
  * appropriate [[ActionHandler]].
  *
  * It is recommended that the Circuit only have a single point of reference
  * in the application, rather than being called on multiple instances. That
  * point is usually the application's router.
  *
  * @tparam M The application model
  */
abstract class Circuit[M <: AnyRef] { self =>
  /**
    * Defines the type to be extracted from an [[ActionHandler]]'s handle
    * method. This is to be used by the Circuit in order to combine the handlers
    * and identify which to dispatch an action to.
    */
  protected type HandlerFunction = (Var[M], Action) => Option[CircuitTask[M]]

  /**
    * The initial value for the application model. To be overridden
    * in the application's implementation of the Circuit.
    */
  protected def initialModel: M

  /**
    * The internal value for the model. This is the value to be updated
    * by the tasks performed within the [[ActionHandler]] which trigger
    * a change in the application state.
    *
    * @note The decision to make use of the Monix [[Var]] type stems
    *       from the fact that it allows easy exposition of the current
    *       value it holds. This is especially useful considering that
    *       this value can be used just as we would a normal variable,
    *       and be passed to action handlers for immediate use.
    *
    * @see For more information on the Var type:
    *      [[https://monix.io/api/3.0/monix/reactive/subjects/Var.html]]
    */
  private val model: Var[M] = Var(initialModel)

  /**
    * Focuses on a specific part of the model to be passed on
    * for `views` to use. This way, the whole model doesn't have
    * to be used within the application views, and they may only
    * choose to observe a specific part of the model (referred to
    * as a [[Particle]]).
    *
    * @param reducer Method to be used when reducing the application model
    *                (converts from model type M to Z, a subset of the model)
    * @tparam Z Usually bounded by a [[Particle]], which is used to represent a
    *           ``model field``, or a subset of the model
    */
  final def observe[Z](reducer: M => Z): ModelObservable[Z] =
    new ModelObservable[Z] {
      override val observable: Observable[Z] = model.map(reducer(_))
      override def value: Z = reducer(model())
      override def dispatcher: (Action, Option[Throwable] => Unit, Option[FiniteDuration]) => Cancelable = self.dispatch
    }

  /**
    * Focuses on a specific part of the model to be passed on
    * for an [[ActionHandler]] to use for reading & writing purposes.
    *
    * @param reducer Method to be used when reducing the application model
    *                (converts from model type M to Z, a subset of the model)
    * @param reverter Method to be used when abstracting the application model
    *                 (converts from subset Z, back to complete model M)
    * @tparam Z Usually bounded by a [[Particle]], which is used to represent a
    *           ``model field``, or a subset of the model
    */
  final protected def zoomRW[Z](implicit reducer: M => Z, reverter: (M, Z) => M): ReducedModel[M, Z] =
    model.map[Z](reducer(_))

  /**
    * Focuses on a specific part of the model to be passed on
    * for an [[ActionHandler]] to use for reading purposes only.
    *
    * @param reducer Method to be used when reducing the application model
    *                (converts from model type M to Z, a subset of the model)
    * @tparam Z Usually bounded by a [[Particle]], which is used to represent a
    *           ``model field``, or a subset of the model
    */
  final protected def zoomR[Z](reducer: M => Z): Option[Z] =
    Option(model()).map(reducer(_))

  /**
    * The single-point action handler. If there are multiple handlers
    * within the application, the [[combineHandlers]] method should be used
    * in order to group them prior to assigning them to this method.
    */
  protected def actionHandler: HandlerFunction

  /**
    * Combines multiple [[ActionHandler]] into a single handler. An
    * implicit conversion will take care of extracting the handling logic
    * into the expected type, [[HandlerFunction]].
    *
    * @param handlers Handlers to be combined
    */
  protected def combineHandlers(handlers: HandlerFunction*): HandlerFunction =
    (currentModel, action) =>
      handlers.foldLeft(Option.empty[CircuitTask[M]]) { (a, b) =>
        a.orElse(b(currentModel, action))
      }

  /**
    * Action dispatcher.
    *
    * The dispatch method is used within application ``views`` to dispatch
    * actions to the Circuit. The view should have a reference to the model
    * through a [[ModelObservable]] in hand, provided by the Circuit with the
    * [[observe]] method, often through the application router. The dispatch method
    * will be accessible along with the observable.
    *
    * @param action The dispatched action to be handled
    * @param onFinish Optional call to append to previous tasks
    * @param pollingRate Optional polling rate. Should one be provided,
    *                    the dispatching will be repeated periodically
    *                    at the given rate
    * @see The [[ModelObservable]] class for the implementation of the dispatch method
    *      that will be available for application views to use.
    */
  private def dispatch(action: Action,
                       onFinish: Option[Throwable] => Unit,
                       pollingRate: Option[FiniteDuration]): Cancelable = {
    def task: Task[Ack] = {
      println(s"[Circuit] Action: ${action.toString.split('(').head}")
      actionHandler(model, action)
        .map { cTask => cTask.pendingValue.foreach(update); cTask.task }
        .getOrElse(Task.raiseError(new Error(s"[Circuit] The dispatched action $action does not exist")))
        .flatMap(m => Task.eval(update(m)))
        .doOnFinish(ex => Task(onFinish(ex)))
    }

    pollingRate.fold[Cancelable] { task.runAsync } { rate =>
      Observable.intervalAtFixedRate(rate)
        .mapTask(_ => task)
        .completedL
        .runAsync
    }
  }

  /**
    * Unsafe dispatcher.
    *
    * @param action Action to be dispatched
    * @note Marked as unsafe since dispatching should generally
    *       be operated through observables, either a [[ModelObservable]]
    *       or a [[zener.ModelObservable.ParticleObservable]]
    *       trickled down the components tree. They both expose a safe
    *       dispatching method.
    */
  final def unsafeDispatch(action: Action): Callback =
    Callback { dispatch(action, _ => (), None) }

  /**
    * Updates the application state with a new model.
    *
    * @param newModel New model to assign to the [[model]] Var
    * @return A Monix acknowledgement of type [[Ack]]. Used as this
    *         is the default type returned by the [[Var]] := operator.
    */
  private def update(newModel: M): Ack =
    if (model() ne newModel) {
      model := newModel
    } else Continue
}
