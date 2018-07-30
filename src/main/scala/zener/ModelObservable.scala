package zener

import japgolly.scalajs.react.Callback
import monix.execution.Cancelable
import monix.reactive.Observable

import scala.concurrent.duration.FiniteDuration

/**
  * The observable reference to the model.
  *
  * This class is generated when zooming onto a specific part of the model
  * for the application views to use (see the [[Circuit.observe]] method).
  *
  * The implicit conversion allows for use either as a Monix Observable,
  * or as an action dispatcher to the [[Circuit]]. This is what links model
  * with view in the application.
  *
  * @tparam T A subset of the model, often bounded by a [[Particle]]
  */
abstract class ModelObservable[+T] { self =>
  /**
    * The observable portion of the model. This is the
    * underlying observable: it is only used for conversion purposes
    * (see companion object).
    */
  private[zener] val observable: Observable[T]

  /**
    * The dispatcher attached to the observable, as defined
    * in the [[Circuit]] (see [[dispatch]]).
    */
  private[zener] def dispatcher: (Action, Option[Throwable] => Unit, Option[FiniteDuration]) => Cancelable

  /**
    * The current value for this section of the model
    */
  def value: T

  /**
    * The dispatch method to be used from application views
    * in order to trigger a change in the application state.
    *
    * @note This is why it is imperative that the [[ModelObservable]]
    *       be declared at a higher level (oftentimes the application
    *       router) and cascade all the way down to the view that uses it.
    *       This way, the view has control over both the Observable (to
    *       be used with a [[ParticleBasedComponent]]) and the dispatcher.
    *
    * @param action Action to be dispatched to the [[Circuit]]
    */
  final def dispatch(action: Action): Callback =
    Callback { dispatcher(action, _ => (), None) }

  /**
    * Alternative to the above [[dispatch]], with an added
    * polling option. This will repeat the task periodically at
    * the given rate.
    *
    * @param action Action to be dispatched to the [[Circuit]]
    * @param pollingRate Polling rate
    *
    * @return A Cancelable. Unlike the other two alternatives, we want
    *         to be able to cancel this one, so that the polling does not
    *         go on infinitely. The view from which the dispatching is
    *         done will be responsible for canceling this repeated task.
    */
  final def dispatch(action: Action, pollingRate: FiniteDuration): Cancelable =
    dispatcher(action, _ => (), Some(pollingRate))

  /**
    * The dispatch method to be used from application views in order
    * to trigger a change in the application state. An alternative
    * method to [[dispatch]]; it also offers the option to react
    * to the outcome of the call through the ``fn`` method.
    *
    * @param action Action to be dispatched to the [[Circuit]]
    * @param fn Method to be called once the task has been performed,
    *           using the result of the task as an input
    */
  final def dispatchCB(action: Action)(fn: Option[Throwable] => Callback): Callback =
    Callback { dispatcher(action, fn.andThen(_.runNow()), None) }

  /**
    * Convert the type within the observable
    *
    * @param fn Mapping function
    */
  final def map[A](fn: T => A): ModelObservable[A] =
    new ModelObservable[A] {
      override val observable: Observable[A] = self.observable.map(fn)
      override def value: A = fn(self.value)
      override def dispatcher: (Action, Option[Throwable] => Unit, Option[FiniteDuration]) => Cancelable = self.dispatcher
    }
}

object ModelObservable {
  /**
    * Generic type for the ``complete`` dispatcher; otherwise,
    * just use Action => Callback.
    */
  type Dispatcher = Action => (Option[Throwable] => Callback) => Callback

  /**
    * Helper type to avoid redundancy when writing Particle
    * observables, a common use case.
    *
    * Since a Particle is considered a "partial" model, it is
    * expected that we want to watch only part of the model
    * for state updates.
    *
    * @tparam T Particled type
    */
  type ParticleObservable[T] = ModelObservable[Particle[T]]

  /**
    * Implicit conversion from a [[ModelObservable]] to an [[Observable]].
    *
    * @todo The ideal solution would be to have [[ModelObservable]] extend
    *       [[Observable]]. However, it is an abstract class which requires
    *       that the [[Observable.unsafeSubscribeFn]] method be implemented,
    *       but any implementation had the UI enter in an undefined loop.
    *       To be investigated.
    *
    * @param mo The model observable to be converted
    */
  implicit def modelObservableToObservable[T](mo: ModelObservable[T]): Observable[T] =
    mo.observable
}
