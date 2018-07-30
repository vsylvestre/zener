package zener

/**
  * The Particle type. A Particle is used to indicate that
  * a given type is part of the application model.
  *
  * It is usually populated following a call to a back-end entity.
  * Therefore, it can be found in many states: available, pending,
  * unavailable, or empty.
  *
  * @tparam A What we expect for the Particle to deliver
  */
sealed abstract class Particle[+A] extends Product with Serializable {
  def isEmpty: Boolean

  def get: A

  final def isDefined: Boolean = !isEmpty

  final def getOrElse[B >: A](default: => B): B =
    if (isEmpty) default else this.get

  final def toOption: Option[A] =
    if (isEmpty) None else Some(this.get)

  final def map[B](f: A => B): Particle[B] = this match {
    case ParticleEmpty          => ParticleEmpty
    case ParticlePending        => ParticlePending
    case ParticlePendingFull(x) => ParticlePendingFull(f(x))
    case ParticleUnavailable    => ParticleUnavailable
    case ParticleReady(x)       => ParticleReady(f(x))
  }

  final def fold[B](ifEmpty: => B)(f: A => B): B =
    if (isEmpty) ifEmpty else f(this.get)

  def flatten[B](implicit ev: A <:< Particle[B]): Particle[B] =
    if (isEmpty) ParticleEmpty else ev(this.get)

  final def flatMap[B](f: A => Particle[B]): Particle[B] = map(f).flatten

  final def pending: Particle[A] =
    if (isEmpty) ParticlePending else ParticlePendingFull(this.get)
}

object Particle {
  def empty: ParticleEmpty.type = ParticleEmpty

  def fromOption[T](option: Option[T]): Particle[T] =
    option.map(apply).getOrElse(empty)

  def apply[A](value: A): Particle[A] =
    if (value == null) ParticleEmpty else ParticleReady(value)
}

final case class ParticleReady[+A](value: A) extends Particle[A] {
  override def isEmpty: Boolean = false
  override def get: A = value
}

case object ParticlePending extends Particle[Nothing] {
  override def isEmpty: Boolean = true
  override def get = throw new NoSuchElementException("ParticlePending.get")
}

case class ParticlePendingFull[+A](value: A) extends Particle[A] {
  override def isEmpty: Boolean = false
  override def get: A = value
}

case object ParticleUnavailable extends Particle[Nothing] {
  override def isEmpty: Boolean = true
  override def get = throw new NoSuchElementException("ParticleUnavailable.get")
}

case object ParticleEmpty extends Particle[Nothing] {
  override def isEmpty: Boolean = true
  override def get = throw new NoSuchElementException("ParticleEmpty.get")
}
