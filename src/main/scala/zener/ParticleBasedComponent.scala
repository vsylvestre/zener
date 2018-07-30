package zener

import japgolly.scalajs.react.component.Scala
import japgolly.scalajs.react.component.Scala.BackendScope
import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.{Callback, ScalaComponent}
import monix.execution.Ack.Continue
import monix.execution.Scheduler.Implicits.global
import monix.execution.{Ack, Cancelable}
import monix.reactive.{Observable, Observer}
import zener.ModelObservable.ParticleObservable

import scala.concurrent.Future

/**
  * A React component based on a model Particle.
  *
  * Should the content of a [[Particle]] be displayed within a React
  * component, this is the class to use. It will take care of the
  * whole subscription logic; only a state-based series of VDom
  * elements should be defined. The component will take care of
  * selecting the right VDom to be displayed, based on the state
  * of the Particle.
  *
  * @param observable The Observable, usually sourced from a
  *                   [[ModelObservable]] type, which contains a
  *                   Particle
  * @tparam Z The type to be found within the Particle
  *           once extracted successfully
  */
sealed class ParticleBasedComponent[Z](val observable: Observable[Particle[Z]]) {
  /**
    * Local properties.
    *
    * @param selector The VDom selector. Based on the state of
    *                 the [[Particle]], decide on which VDom to
    *                 display.
    */
  case class MOProps(selector: PartialFunction[Particle[Z], VdomElement])

  /**
    * Local state.
    *
    * @param particle The state. This value will be updated based
    *            on the events dispatched by the Observable
    *            to the subscribers (including this one).
    */
  case class MOState(particle: Particle[Z] = ParticleEmpty)

  sealed class MOBackend($: BackendScope[MOProps, MOState]) { self =>

    // Cancelable observer of the application model
    private var modelObserver: Cancelable = _

    /**
      * React lifecycle: componentDidMount
      *
      * Subscribes our private observer to the
      * ParticleObservable.
      */
    def didMount: Callback =
      Callback { modelObserver = observable.subscribe(new ModelObserver(self)) }

    /**
      * React lifecyle: componentWillUnmount
      *
      * Cancels observation logic initiated on
      * mounting of the component.
      */
    def willUnmount: Callback =
      Callback { modelObserver.cancel() }

    /**
      * Following a new update from the application model,
      * update the state of the local [[Particle]] and,
      * consequently, the displayed VDom, should the state
      * have changed.
      *
      * @param newState New state for the [[Particle]].
      */
    def setObservableState(newState: Particle[Z]): Callback =
      $.state.flatMap { state =>
        val updateCondition: Boolean = newState.flatMap(p => state.particle.map(_ != p)).getOrElse(true)
        Callback.when(updateCondition) {
          $.modState(_.copy(particle = newState))
        }
      }

    def render(props: MOProps, state: MOState): VdomElement =
      props.selector(state.particle)
  }

  private class ModelObserver(backend: MOBackend) extends Observer[Particle[Z]] {

    override def onNext(elem: Particle[Z]): Future[Ack] = {
      val newState =
        elem match {
          case null => ParticleEmpty
          case _ => elem
        }

      backend.setObservableState(newState).runNow()
      Continue
    }

    override def onComplete(): Unit = ()

    override def onError(ex: Throwable): Unit = {
      ex.printStackTrace()
      backend.setObservableState(ParticleUnavailable).runNow()
    }
  }

  private val component = ScalaComponent.builder[MOProps]("ParticleBased")
    .initialState(MOState())
    .renderBackend[MOBackend]
    .componentDidMount(_.backend.didMount)
    .componentWillUnmount(_.backend.willUnmount)
    .build

  /**
    * Render method for the [[Particle]].
    *
    * @param vdom VDom to be displayed once the content of
    *             the [[Particle]] is exposed by the model
    * @param loadingVdom VDom to be displayed while the content
    *                    is loading (or, at least, as long as it
    *                    is unavailable). For more control over this
    *                    state, use the alternative [[renderWithSelector]]
    *                    method.
    */
  def render(vdom: Z => VdomElement, loadingVdom: VdomElement = VdomElement(null)): Scala.Unmounted[MOProps, MOState, MOBackend] = {
    val selector: PartialFunction[Particle[Z], VdomElement] = {
      case ParticleEmpty => loadingVdom
      case ParticlePending => loadingVdom
      case z: Particle[Z] => z.toOption.fold(VdomElement(null))(vdom)
    }
    component(MOProps(selector))
  }

  /**
    * Render method for the [[Particle]] expecting
    * a complete state manager (the selector).
    *
    * @param selector Partial function defining what to
    *                 display for given states of the Particle.
    *                 For a more general use of the component
    *                 without specific state management, use the
    *                 alternative [[render]] method.
    */
  def renderWithSelector(selector: PartialFunction[Particle[Z], VdomElement]): Scala.Unmounted[MOProps, MOState, MOBackend] =
    component(MOProps(selector))
}

object ParticleBasedComponent {

  def apply[Z](observable: ParticleObservable[Z]) =
    new ParticleBasedComponent[Z](observable)

  def apply[Z1, Z2](observable1: Observable[Particle[Z1]], observable2: Observable[Particle[Z2]]) =
    new ParticleBasedComponent[(Z1, Z2)](
      Observable.combineLatest2(observable1, observable2)
        .map { case (op1, op2) =>
            if (op1 == ParticlePending || op2 == ParticlePending)
              ParticlePending
            else
              (for (p1 <- op1; p2 <- op2) yield Particle((p1, p2))).getOrElse(Particle.empty)
        }
    )

  def apply[Z1, Z2, Z3](observable1: Observable[Particle[Z1]],
                        observable2: Observable[Particle[Z2]],
                        observable3: Observable[Particle[Z3]]) =
    new ParticleBasedComponent[(Z1, Z2, Z3)](
      Observable.combineLatest3(observable1, observable2, observable3)
        .map { case (op1, op2, op3) =>
          if (op1 == ParticlePending || op2 == ParticlePending || op3 == ParticlePending)
            ParticlePending
          else
            (for (p1 <- op1; p2 <- op2; p3 <- op3) yield Particle((p1, p2, p3))).getOrElse(Particle.empty)
        }
    )

  def apply[Z1, Z2, Z3, Z4](observable1: Observable[Particle[Z1]],
                            observable2: Observable[Particle[Z2]],
                            observable3: Observable[Particle[Z3]],
                            observable4: Observable[Particle[Z4]]) =
    new ParticleBasedComponent[(Z1, Z2, Z3, Z4)](
      Observable.combineLatest4(observable1, observable2, observable3, observable4)
        .map { case (op1, op2, op3, op4) =>
          if (op1 == ParticlePending || op2 == ParticlePending || op3 == ParticlePending || op4 == ParticlePending)
            ParticlePending
          else
            (for (p1 <- op1; p2 <- op2; p3 <- op3; p4 <- op4) yield Particle((p1, p2, p3, p4))).getOrElse(Particle.empty)
        }
    )
}
