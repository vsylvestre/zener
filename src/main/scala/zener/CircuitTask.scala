package zener

import monix.eval.Task

/**
  * Represents a Task as performed by the Circuit
  *
  * Working with custom tasks allows Zener to append
  * a `pending value` to them. In other words, Zener
  * will be able to present the consumer with an alternative
  * `while` the task is being performed. This is especially
  * useful for displaying specific views while the
  * content is loading on the UI.
  *
  * @tparam M Application model
  */
sealed trait CircuitTask[M] {
  /**
    * This is the task to be performed.
    *
    * @note As the [[Task]] class cannot be extended, one has
    *       to call on this value in order to get access
    *       to the task: [[CircuitTask]] only acts as a
    *       wrapper.
    */
  def task: Task[M]

  /**
    * This is the `default` value for the content
    * of the model, while the [[task]] is being
    * performed.
    *
    * @return An optional value for the model; if none
    *         is provided, no pending action will be dispatched
    *         by the [[Circuit]].
    */
  def pendingValue: Option[M] = None
}

/**
  * Represents a Loading task, as produced by
  * the [[ActionHandler.load]] method
  *
  * @tparam M Application model
  */
trait LoadTask[M] extends CircuitTask[M] {
  def whileLoading: Option[M]
  override def pendingValue: Option[M] = whileLoading
}

/**
  * Represents an Update task, as produced by
  * the [[ActionHandler.update]] method
  *
  * @tparam M Application model
  */
trait UpdateTask[M] extends CircuitTask[M] {
  override def pendingValue: Option[M] = None
}

/**
  * Represents a task that results in no change
  * on the model, as produced by the
  * [[ActionHandler.noChange]] method
  *
  * @tparam M Application model
  */
trait NoChangeTask[M] extends CircuitTask[M] {
  override def pendingValue: Option[M] = None
}
